package com.fams.modules.subscription.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.subscription.dto.request.CancelBillingOrderRequest;
import com.fams.modules.subscription.dto.request.CreateBillingOrderRequest;
import com.fams.modules.subscription.dto.response.BillingOrderResponse;
import com.fams.modules.subscription.entity.BillingOrder;
import com.fams.modules.subscription.entity.BillingOrder.BillingOrderStatus;
import com.fams.modules.subscription.entity.BillingOrder.BillingInvoiceStatus;
import com.fams.modules.subscription.entity.Plan;
import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import com.fams.modules.subscription.repository.BillingOrderRepository;
import com.fams.modules.subscription.repository.PlanRepository;
import com.fams.modules.subscription.repository.TenantSubscriptionRepository;
import com.fams.modules.subscription.specification.BillingOrderSpecification;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.security.HttpRequestUtils;
import com.fams.shared.time.VietnamTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class BillingOrderService {

    // payOS limits `description` to 9 characters for bank accounts that are not linked
    // directly through payOS. Keep the universal/lower limit so the same payment channel
    // remains valid regardless of how its receiving bank account is connected.
    static final int PAYOS_DESCRIPTION_MAX_LENGTH = 9;

    private static final EnumSet<BillingOrderStatus> OPEN_STATUSES = EnumSet.of(
            BillingOrderStatus.CREATING, BillingOrderStatus.PENDING,
            BillingOrderStatus.PROCESSING, BillingOrderStatus.UNDERPAID);
    private static final Map<String, String> PLATFORM_SORT_FIELDS = Map.of(
            "createdAt", "createdAt",
            "amount", "amount",
            "paidAt", "paidAt",
            "company", "tenantNameSnapshot",
            "status", "status",
            "orderCode", "orderCode");

    private final BillingOrderRepository orderRepository;
    private final PlanRepository planRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PaymentGateway paymentGateway;
    private final TenantSubscriptionService subscriptionService;
    private final AuditLogService auditLogService;
    private final String frontendUrl;
    private final long paymentLinkExpiryMinutes;

    public BillingOrderService(BillingOrderRepository orderRepository,
                               PlanRepository planRepository,
                               TenantSubscriptionRepository subscriptionRepository,
                               TenantRepository tenantRepository,
                               UserRepository userRepository,
                               PaymentGateway paymentGateway,
                               TenantSubscriptionService subscriptionService,
                               AuditLogService auditLogService,
                               @Value("${app.frontend-url:http://localhost:3000}") String frontendUrl,
                               @Value("${app.billing.payment-link-expiry-minutes:30}") long paymentLinkExpiryMinutes) {
        this.orderRepository = orderRepository;
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.paymentGateway = paymentGateway;
        this.subscriptionService = subscriptionService;
        this.auditLogService = auditLogService;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
        this.paymentLinkExpiryMinutes = Math.max(5, paymentLinkExpiryMinutes);
    }

    /**
     * Persists the local order before calling payOS. This deliberately is not one large database
     * transaction: a slow provider call must not hold locks, and a failed provider call remains
     * visible to Billing Ops instead of disappearing through transaction rollback.
     */
    public BillingOrderResponse createOrder(UUID tenantId, CreateBillingOrderRequest request,
                                             UUID callerUserId, boolean platformAdmin) {
        Tenant tenant = requireTenantAccess(tenantId, callerUserId, platformAdmin);
        if ("cancelled".equalsIgnoreCase(tenant.getStatus())) {
            throw new IllegalStateException("Cancelled companies cannot purchase a subscription");
        }
        expireStaleOrders(tenantId);
        if (orderRepository.existsByTenantIdAndStatusIn(tenantId, OPEN_STATUSES)) {
            throw new DuplicateResourceException("Tenant already has an unfinished billing order");
        }

        Plan plan = planRepository.findByIdAndDeletedAtIsNull(request.getPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + request.getPlanId()));
        if (!plan.isActive()) throw new IllegalStateException("This plan is not available for purchase");

        OffsetDateTime now = OffsetDateTime.now();
        subscriptionRepository.findByTenantId(tenantId)
                .filter(subscription -> subscription.getStatus()
                        == com.fams.modules.subscription.entity.TenantSubscription.SubscriptionStatus.ACTIVE)
                .filter(subscription -> subscription.getExpiresAt() == null
                        || subscription.getExpiresAt().isAfter(now))
                .filter(subscription -> plan.getId().equals(subscription.getPlanId()))
                .ifPresent(subscription -> {
                    throw new SubscriptionPurchaseConflictException(plan.getDisplayName());
                });

        long amount = toVndAmount(request.getBillingCycle() == com.fams.modules.subscription.entity.TenantSubscription.BillingCycle.YEARLY
                ? plan.getPriceYearly() : plan.getPriceMonthly());
        long orderCode = orderRepository.nextOrderCode();
        String paymentDescription = buildPayOsDescription(orderCode);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(paymentLinkExpiryMinutes);
        BillingOrder order = BillingOrder.builder()
                .orderCode(orderCode)
                .tenantId(tenantId)
                .tenantNameSnapshot(tenant.getName())
                .planId(plan.getId())
                .planNameSnapshot(plan.getName())
                .planDisplaySnapshot(plan.getDisplayName())
                .billingCycle(request.getBillingCycle())
                .amount(amount)
                .amountPaid(0L)
                .currency("VND")
                .status(BillingOrderStatus.CREATING)
                .invoiceStatus(BillingInvoiceStatus.NOT_ELIGIBLE)
                .providerStatus("CREATING")
                .createdBy(callerUserId)
                .expiresAt(expiresAt)
                .build();
        orderRepository.saveAndFlush(order);

        User buyer = userRepository.findByIdAndDeletedAtIsNull(callerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + callerUserId));
        String resultUrl = frontendUrl + "/customer/billing/result?billingOrderId=" + order.getId();
        try {
            PaymentGateway.CreatedLink link = paymentGateway.createPaymentLink(
                    new PaymentGateway.CreateLinkCommand(
                            orderCode, amount, paymentDescription, plan.getDisplayName(), tenant.getName(),
                            buyer.getEmail(), resultUrl, resultUrl, expiresAt));
            order.setPaymentLinkId(link.paymentLinkId());
            order.setCheckoutUrl(link.checkoutUrl());
            order.setQrCode(link.qrCode());
            order.setProviderStatus(link.status());
            order.setStatus(mapOpenProviderStatus(link.status()));
            orderRepository.save(order);
            recordAudit(order, callerUserId, "billing_order_created", null, snapshot(order));
            return toResponse(order);
        } catch (RuntimeException ex) {
            order.setStatus(BillingOrderStatus.FAILED);
            order.setProviderStatus("CREATE_FAILED");
            String failure = providerFailureReason(ex);
            order.setFailureReason(trim(failure, 500));
            orderRepository.save(order);
            log.warn("Checkout creation failed for orderCode={} tenantId={}: {}",
                    orderCode, tenantId, failure, ex);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<BillingOrderResponse> listTenantOrders(UUID tenantId, UUID callerUserId,
                                                                boolean platformAdmin, int page, int size) {
        requireTenantAccess(tenantId, callerUserId, platformAdmin);
        return PageResponse.from(orderRepository.findAllByTenantId(
                tenantId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))).map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public BillingOrderResponse getTenantOrder(UUID tenantId, UUID orderId, UUID callerUserId,
                                                boolean platformAdmin) {
        requireTenantAccess(tenantId, callerUserId, platformAdmin);
        return toResponse(orderRepository.findByIdAndTenantId(orderId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Billing order not found: " + orderId)));
    }

    @Transactional(readOnly = true)
    public PageResponse<BillingOrderResponse> listPlatformOrders(String search, UUID tenantId,
                                                                  BillingOrderStatus status,
                                                                  BillingCycle billingCycle,
                                                                  String sortBy, String sortDir,
                                                                  int page, int size) {
        String property = PLATFORM_SORT_FIELDS.getOrDefault(sortBy, "createdAt");
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir)
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        var pageable = PageRequest.of(page, size, Sort.by(direction, property));
        return PageResponse.from(orderRepository.findAll(
                        BillingOrderSpecification.build(search, tenantId, status, billingCycle), pageable)
                .map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public BillingOrderResponse getPlatformOrder(UUID orderId) {
        return toResponse(requireOrder(orderId));
    }

    @Transactional
    public BillingOrderResponse cancelTenantOrder(UUID tenantId, UUID orderId,
                                                   CancelBillingOrderRequest request,
                                                   UUID callerUserId, boolean platformAdmin) {
        requireTenantAccess(tenantId, callerUserId, platformAdmin);
        BillingOrder order = orderRepository.findByIdAndTenantId(orderId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Billing order not found: " + orderId));
        return cancel(order, request != null ? request.getReason() : null, callerUserId);
    }

    @Transactional
    public BillingOrderResponse cancelPlatformOrder(UUID orderId, CancelBillingOrderRequest request,
                                                     UUID callerUserId) {
        return cancel(requireOrder(orderId), request != null ? request.getReason() : null, callerUserId);
    }

    @Transactional
    public BillingOrderResponse refreshOrder(UUID orderId, UUID actorUserId) {
        BillingOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Billing order not found: " + orderId));
        if (order.getStatus() == BillingOrderStatus.PAID && order.getSubscriptionAppliedAt() != null) {
            return toResponse(order);
        }
        if (order.getPaymentLinkId() == null) {
            if (order.getStatus() == BillingOrderStatus.CREATING
                    && order.getUpdatedAt() != null
                    && order.getUpdatedAt().isBefore(OffsetDateTime.now().minusMinutes(2))) {
                order.setStatus(BillingOrderStatus.FAILED);
                order.setProviderStatus("CREATE_INTERRUPTED");
                order.setFailureReason("Quá trình tạo liên kết thanh toán bị gián đoạn. Vui lòng tạo đơn mới.");
                orderRepository.save(order);
            }
            return toResponse(order);
        }
        PaymentGateway.ProviderPayment provider = paymentGateway.getPayment(order.getOrderCode());
        applyProviderState(order, provider, actorUserId);
        return toResponse(order);
    }

    @Transactional
    public void processWebhook(Map<String, Object> body) {
        PaymentGateway.VerifiedWebhook verified = paymentGateway.verifyWebhook(body);
        BillingOrder order = orderRepository.findByOrderCodeForUpdate(verified.orderCode()).orElse(null);
        // payOS sends a signed sample payload while confirming a webhook URL. A valid but unknown
        // order must be acknowledged so initial webhook registration can succeed.
        if (order == null) {
            log.info("Acknowledged verified payOS webhook for unknown/sample orderCode={}", verified.orderCode());
            return;
        }
        if (StringUtils.hasText(order.getPaymentLinkId())
                && !order.getPaymentLinkId().equals(verified.paymentLinkId())) {
            throw new InvalidPaymentWebhookException("paymentLinkId does not match local order", null);
        }
        if (verified.amount() <= 0 || !"00".equals(verified.code())) {
            throw new InvalidPaymentWebhookException("webhook does not represent a successful payment", null);
        }
        if (StringUtils.hasText(verified.currency()) && !"VND".equalsIgnoreCase(verified.currency())) {
            throw new InvalidPaymentWebhookException("webhook currency is not VND", null);
        }

        PaymentGateway.ProviderPayment provider = paymentGateway.getPayment(order.getOrderCode());
        if (StringUtils.hasText(verified.reference())) {
            if (orderRepository.existsByPaymentReferenceAndIdNot(verified.reference(), order.getId())) {
                throw new InvalidPaymentWebhookException("payment reference belongs to another order", null);
            }
            order.setPaymentReference(verified.reference());
        }
        applyProviderState(order, provider, null);
    }

    /** Called by the reconciliation scheduler; errors are isolated per order by the caller. */
    public void reconcile(UUID orderId) {
        refreshOrder(orderId, null);
    }

    private void applyProviderState(BillingOrder order, PaymentGateway.ProviderPayment provider, UUID actorUserId) {
        if (provider.orderCode() != order.getOrderCode()) {
            throw new InvalidPaymentWebhookException("provider orderCode does not match local order", null);
        }
        if (provider.expectedAmount() != order.getAmount()) {
            throw new InvalidPaymentWebhookException("provider expected amount does not match local order", null);
        }
        if (StringUtils.hasText(order.getPaymentLinkId())
                && !order.getPaymentLinkId().equals(provider.paymentLinkId())) {
            throw new InvalidPaymentWebhookException("provider paymentLinkId does not match local order", null);
        }

        order.setAmountPaid(Math.max(0, provider.amountPaid()));
        order.setProviderStatus(provider.status());
        if (StringUtils.hasText(provider.reference())) {
            if (orderRepository.existsByPaymentReferenceAndIdNot(provider.reference(), order.getId())) {
                throw new InvalidPaymentWebhookException("provider reference belongs to another order", null);
            }
            order.setPaymentReference(provider.reference());
        }

        if (order.getAmountPaid() >= order.getAmount() && "PAID".equalsIgnoreCase(provider.status())) {
            order.setStatus(BillingOrderStatus.PAID);
            if (order.getInvoiceStatus() == null
                    || order.getInvoiceStatus() == BillingInvoiceStatus.NOT_ELIGIBLE) {
                order.setInvoiceStatus(BillingInvoiceStatus.PENDING_ISSUANCE);
            }
            if (order.getSubscriptionAppliedAt() == null) {
                OffsetDateTime paidAt = order.getPaidAt() != null ? order.getPaidAt() : OffsetDateTime.now();
                subscriptionService.activateFromPayment(order.getTenantId(), order.getPlanId(),
                        order.getBillingCycle(), paidAt, order.getId());
                order.setPaidAt(paidAt);
                order.setSubscriptionAppliedAt(OffsetDateTime.now());
                recordAudit(order, actorUserId, "billing_order_paid", null, snapshot(order));
            }
            order.setFailureReason(null);
        } else if (order.getAmountPaid() > 0) {
            order.setStatus(BillingOrderStatus.UNDERPAID);
            order.setInvoiceStatus(BillingInvoiceStatus.PAYMENT_REVIEW);
            order.setFailureReason("Số tiền nhận được chưa đủ để kích hoạt gói");
        } else if ("CANCELLED".equalsIgnoreCase(provider.status())) {
            order.setStatus(BillingOrderStatus.CANCELLED);
            if (order.getCancelledAt() == null) order.setCancelledAt(OffsetDateTime.now());
        } else if (OffsetDateTime.now().isAfter(order.getExpiresAt())) {
            order.setStatus(BillingOrderStatus.EXPIRED);
        } else {
            order.setStatus(mapOpenProviderStatus(provider.status()));
        }
        orderRepository.save(order);
    }

    private BillingOrderResponse cancel(BillingOrder order, String reason, UUID actorUserId) {
        if (!order.isOpen()) throw new IllegalStateException("Only an unfinished billing order can be cancelled");
        if (order.getPaymentLinkId() != null) {
            PaymentGateway.ProviderPayment provider = paymentGateway.cancelPayment(
                    order.getOrderCode(), StringUtils.hasText(reason) ? reason.trim() : "Customer requested cancellation");
            if (provider.amountPaid() > 0 || "PAID".equalsIgnoreCase(provider.status())) {
                return refreshOrder(order.getId(), actorUserId);
            }
            order.setProviderStatus(provider.status());
        }
        order.setStatus(BillingOrderStatus.CANCELLED);
        order.setCancelledAt(OffsetDateTime.now());
        order.setFailureReason(StringUtils.hasText(reason) ? trim(reason.trim(), 500) : null);
        orderRepository.save(order);
        recordAudit(order, actorUserId, "billing_order_cancelled", null, snapshot(order));
        return toResponse(order);
    }

    private void expireStaleOrders(UUID tenantId) {
        OffsetDateTime now = OffsetDateTime.now();
        for (BillingOrder order : orderRepository.findAllByTenantIdAndStatusIn(tenantId, OPEN_STATUSES)) {
            if (order.getExpiresAt().isBefore(now)) {
                order.setStatus(BillingOrderStatus.EXPIRED);
                order.setProviderStatus("EXPIRED");
                orderRepository.save(order);
            }
        }
    }

    private Tenant requireTenantAccess(UUID tenantId, UUID callerUserId, boolean platformAdmin) {
        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
        if (!platformAdmin && !callerUserId.equals(tenant.getOwnerId())) {
            throw new AccessDeniedException("Only the company owner can manage billing");
        }
        return tenant;
    }

    private BillingOrder requireOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Billing order not found: " + orderId));
    }

    private long toVndAmount(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalStateException("The selected paid plan does not have a valid price");
        }
        try {
            return price.longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("Plan price must be a whole VND amount");
        }
    }

    /**
     * Generates a short, ASCII-only bank transfer description. Base-36 preserves uniqueness
     * for more than 2.8 trillion sequential orders while fitting the strict 9-character payOS
     * limit (one FAMS prefix plus eight encoded characters). Refuse values outside that range
     * instead of silently truncating and risking two orders sharing the same description.
     */
    static String buildPayOsDescription(long orderCode) {
        if (orderCode <= 0) {
            throw new IllegalArgumentException("Billing order code must be positive");
        }
        String description = "F" + Long.toString(orderCode, Character.MAX_RADIX)
                .toUpperCase(Locale.ROOT);
        if (description.length() > PAYOS_DESCRIPTION_MAX_LENGTH) {
            throw new IllegalStateException("Billing order code exceeds the payOS description capacity");
        }
        return description;
    }

    private BillingOrderStatus mapOpenProviderStatus(String providerStatus) {
        if ("PROCESSING".equalsIgnoreCase(providerStatus)) return BillingOrderStatus.PROCESSING;
        return BillingOrderStatus.PENDING;
    }

    private BillingOrderResponse toResponse(BillingOrder order) {
        boolean receiptAvailable = order.getStatus() == BillingOrderStatus.PAID && order.getPaidAt() != null;
        BillingInvoiceStatus invoiceStatus = order.getInvoiceStatus();
        if (invoiceStatus == null) {
            invoiceStatus = order.getStatus() == BillingOrderStatus.PAID
                    ? BillingInvoiceStatus.PENDING_ISSUANCE
                    : order.getAmountPaid() != null && order.getAmountPaid() > 0
                            ? BillingInvoiceStatus.PAYMENT_REVIEW
                            : BillingInvoiceStatus.NOT_ELIGIBLE;
        }
        return BillingOrderResponse.builder()
                .id(order.getId()).orderCode(order.getOrderCode()).tenantId(order.getTenantId())
                .tenantName(order.getTenantNameSnapshot())
                .planId(order.getPlanId()).planName(order.getPlanNameSnapshot())
                .planDisplayName(order.getPlanDisplaySnapshot()).billingCycle(order.getBillingCycle())
                .amount(order.getAmount()).amountPaid(order.getAmountPaid()).currency(order.getCurrency())
                .status(order.getStatus()).paymentLinkId(order.getPaymentLinkId())
                .checkoutUrl(order.getCheckoutUrl()).qrCode(order.getQrCode())
                .paymentReference(order.getPaymentReference()).providerStatus(order.getProviderStatus())
                .failureReason(order.getFailureReason()).expiresAt(order.getExpiresAt())
                .paidAt(order.getPaidAt()).cancelledAt(order.getCancelledAt())
                .subscriptionAppliedAt(order.getSubscriptionAppliedAt())
                .paymentReceiptAvailable(receiptAvailable)
                .paymentReceiptNumber(receiptAvailable ? buildPaymentReceiptNumber(order) : null)
                .paymentReceiptIssuedAt(receiptAvailable ? order.getPaidAt() : null)
                .invoiceStatus(invoiceStatus).invoiceNumber(order.getInvoiceNumber())
                .invoiceIssuedAt(order.getInvoiceIssuedAt()).invoiceLookupUrl(order.getInvoiceLookupUrl())
                .createdAt(order.getCreatedAt()).updatedAt(order.getUpdatedAt()).build();
    }

    private String buildPaymentReceiptNumber(BillingOrder order) {
        String month = order.getPaidAt().atZoneSameInstant(VietnamTime.ZONE)
                .format(DateTimeFormatter.ofPattern("yyyyMM"));
        return "PT-" + month + "-" + order.getOrderCode();
    }

    private Map<String, Object> snapshot(BillingOrder order) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("orderCode", order.getOrderCode());
        value.put("planId", order.getPlanId().toString());
        value.put("billingCycle", order.getBillingCycle().name());
        value.put("amount", order.getAmount());
        value.put("amountPaid", order.getAmountPaid());
        value.put("currency", order.getCurrency());
        value.put("status", order.getStatus().name());
        value.put("invoiceStatus", order.getInvoiceStatus() != null ? order.getInvoiceStatus().name() : null);
        value.put("providerStatus", order.getProviderStatus());
        return value;
    }

    private void recordAudit(BillingOrder order, UUID actorId, String action,
                             Map<String, Object> oldValue, Map<String, Object> newValue) {
        try {
            auditLogService.record(order.getTenantId(), actorId, null, "BillingOrder",
                    order.getId().toString(), action, oldValue, newValue,
                    HttpRequestUtils.currentRequestId(), HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception ex) {
            log.warn("Failed to record {} audit for billingOrderId={}: {}", action, order.getId(), ex.getMessage());
        }
    }

    private String trim(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private String providerFailureReason(Throwable error) {
        Throwable current = error;
        String detail = null;
        while (current != null) {
            if (StringUtils.hasText(current.getMessage())) detail = current.getMessage().trim();
            current = current.getCause();
        }
        return StringUtils.hasText(detail) ? detail : error.getClass().getSimpleName();
    }
}
