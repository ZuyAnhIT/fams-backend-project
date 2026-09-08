package com.fams.modules.subscription.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.subscription.entity.BillingOrder;
import com.fams.modules.subscription.entity.BillingOrder.BillingOrderStatus;
import com.fams.modules.subscription.entity.BillingOrder.BillingInvoiceStatus;
import com.fams.modules.subscription.entity.Plan;
import com.fams.modules.subscription.entity.TenantSubscription;
import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import com.fams.modules.subscription.repository.BillingOrderRepository;
import com.fams.modules.subscription.repository.PlanRepository;
import com.fams.modules.subscription.repository.TenantSubscriptionRepository;
import com.fams.modules.subscription.dto.request.CreateBillingOrderRequest;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingOrderServiceTest {

    @Mock BillingOrderRepository orderRepository;
    @Mock PlanRepository planRepository;
    @Mock TenantSubscriptionRepository tenantSubscriptionRepository;
    @Mock TenantRepository tenantRepository;
    @Mock UserRepository userRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock TenantSubscriptionService subscriptionService;
    @Mock AuditLogService auditLogService;

    private BillingOrderService service;

    @BeforeEach
    void setUp() {
        service = new BillingOrderService(orderRepository, planRepository, tenantSubscriptionRepository, tenantRepository,
                userRepository, paymentGateway, subscriptionService, auditLogService,
                "http://localhost:3000", 30);
    }

    @Test
    void verifiedPaidWebhookActivatesSubscriptionOnlyOnce() {
        BillingOrder order = pendingOrder();
        Map<String, Object> payload = Map.of("signature", "signed", "data", Map.of("orderCode", 100001));
        when(paymentGateway.verifyWebhook(payload)).thenReturn(
                new PaymentGateway.VerifiedWebhook(100001, 500_000, "VND", "link-1", "bank-ref-1", "00"));
        when(orderRepository.findByOrderCodeForUpdate(100001L)).thenReturn(Optional.of(order));
        when(paymentGateway.getPayment(100001L)).thenReturn(
                new PaymentGateway.ProviderPayment(100001, 500_000, 500_000, "link-1", "PAID", "bank-ref-1"));

        service.processWebhook(payload);
        service.processWebhook(payload);

        assertThat(order.getStatus()).isEqualTo(BillingOrderStatus.PAID);
        assertThat(order.getAmountPaid()).isEqualTo(500_000);
        assertThat(order.getSubscriptionAppliedAt()).isNotNull();
        assertThat(order.getInvoiceStatus()).isEqualTo(BillingInvoiceStatus.PENDING_ISSUANCE);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        var response = service.getPlatformOrder(order.getId());
        assertThat(response.getTenantName()).isEqualTo("Công ty FOFO");
        assertThat(response.isPaymentReceiptAvailable()).isTrue();
        assertThat(response.getPaymentReceiptNumber()).startsWith("PT-").endsWith("-100001");
        assertThat(response.getInvoiceNumber()).isNull();
        verify(subscriptionService, times(1)).activateFromPayment(
                eq(order.getTenantId()), eq(order.getPlanId()), eq(BillingCycle.MONTHLY),
                any(OffsetDateTime.class), eq(order.getId()));
    }

    @Test
    void signedUnknownWebhookIsAcknowledgedForPayosUrlConfirmation() {
        Map<String, Object> payload = Map.of("signature", "signed");
        when(paymentGateway.verifyWebhook(payload)).thenReturn(
                new PaymentGateway.VerifiedWebhook(123, 2_000, "VND", "sample", "sample-ref", "00"));
        when(orderRepository.findByOrderCodeForUpdate(123L)).thenReturn(Optional.empty());

        service.processWebhook(payload);

        verify(paymentGateway, never()).getPayment(anyLong());
        verifyNoInteractions(subscriptionService);
    }

    @Test
    void providerAmountMismatchNeverActivatesSubscription() {
        BillingOrder order = pendingOrder();
        Map<String, Object> payload = Map.of("signature", "signed");
        when(paymentGateway.verifyWebhook(payload)).thenReturn(
                new PaymentGateway.VerifiedWebhook(100001, 100_000, "VND", "link-1", "bank-ref-1", "00"));
        when(orderRepository.findByOrderCodeForUpdate(100001L)).thenReturn(Optional.of(order));
        when(paymentGateway.getPayment(100001L)).thenReturn(
                new PaymentGateway.ProviderPayment(100001, 100_000, 100_000, "link-1", "PAID", "bank-ref-1"));

        assertThatThrownBy(() -> service.processWebhook(payload))
                .isInstanceOf(InvalidPaymentWebhookException.class);
        verifyNoInteractions(subscriptionService);
    }

    @Test
    void paymentDescriptionAlwaysFitsStrictPayosNineCharacterLimit() {
        assertThat(BillingOrderService.buildPayOsDescription(100_000L)).isEqualTo("F255S");
        assertThat(BillingOrderService.buildPayOsDescription(2_821_109_907_455L))
                .isEqualTo("FZZZZZZZZ")
                .hasSize(BillingOrderService.PAYOS_DESCRIPTION_MAX_LENGTH);
    }

    @Test
    void paymentDescriptionRejectsInvalidOrUnrepresentableOrderCodes() {
        assertThatThrownBy(() -> BillingOrderService.buildPayOsDescription(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillingOrderService.buildPayOsDescription(2_821_109_907_456L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void activeEffectiveSubscriptionCannotPurchaseTheSamePlanAgain() {
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        Tenant tenant = Tenant.builder().id(tenantId).ownerId(ownerId).status("active").build();
        Plan plan = Plan.builder().id(planId).displayName("Doanh nghiệp").isActive(true).build();
        TenantSubscription subscription = TenantSubscription.builder()
                .tenantId(tenantId)
                .planId(planId)
                .status(TenantSubscription.SubscriptionStatus.ACTIVE)
                .expiresAt(OffsetDateTime.now().plusDays(5))
                .build();
        CreateBillingOrderRequest request = new CreateBillingOrderRequest();
        request.setPlanId(planId);
        request.setBillingCycle(BillingCycle.MONTHLY);

        when(tenantRepository.findByIdAndDeletedAtIsNull(tenantId)).thenReturn(Optional.of(tenant));
        when(orderRepository.existsByTenantIdAndStatusIn(eq(tenantId), any())).thenReturn(false);
        when(planRepository.findByIdAndDeletedAtIsNull(planId)).thenReturn(Optional.of(plan));
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service.createOrder(tenantId, request, ownerId, false))
                .isInstanceOf(SubscriptionPurchaseConflictException.class)
                .hasMessageContaining("effective active subscription");

        verify(orderRepository, never()).nextOrderCode();
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void expiredSubscriptionMayPurchaseTheSamePlanAgain() {
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        Tenant tenant = Tenant.builder().id(tenantId).ownerId(ownerId).status("active").name("Công ty A").build();
        Plan plan = Plan.builder().id(planId).name("starter").displayName("Khởi đầu")
                .priceMonthly(java.math.BigDecimal.valueOf(10_000)).isActive(true).build();
        TenantSubscription subscription = TenantSubscription.builder()
                .tenantId(tenantId)
                .planId(planId)
                .status(TenantSubscription.SubscriptionStatus.ACTIVE)
                .expiresAt(OffsetDateTime.now().minusSeconds(1))
                .build();
        CreateBillingOrderRequest request = new CreateBillingOrderRequest();
        request.setPlanId(planId);
        request.setBillingCycle(BillingCycle.MONTHLY);

        when(tenantRepository.findByIdAndDeletedAtIsNull(tenantId)).thenReturn(Optional.of(tenant));
        when(orderRepository.existsByTenantIdAndStatusIn(eq(tenantId), any())).thenReturn(false);
        when(planRepository.findByIdAndDeletedAtIsNull(planId)).thenReturn(Optional.of(plan));
        when(tenantSubscriptionRepository.findByTenantId(tenantId)).thenReturn(Optional.of(subscription));
        when(orderRepository.nextOrderCode()).thenReturn(1_700_000_000_000L);
        when(userRepository.findByIdAndDeletedAtIsNull(ownerId)).thenReturn(Optional.of(
                com.fams.modules.auth.entity.User.builder().id(ownerId).email("owner@example.com").build()));
        when(paymentGateway.createPaymentLink(any())).thenReturn(
                new PaymentGateway.CreatedLink("link-new", "https://pay.payos.vn/new", "qr", "PENDING"));

        var result = service.createOrder(tenantId, request, ownerId, false);

        assertThat(result.getStatus()).isEqualTo(BillingOrderStatus.PENDING);
        verify(paymentGateway).createPaymentLink(any());
    }

    @Test
    void unfinishedOrderHasDetailsButNoReceiptOrInvoice() {
        BillingOrder order = pendingOrder();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        var response = service.getPlatformOrder(order.getId());

        assertThat(response.getTenantName()).isEqualTo("Công ty FOFO");
        assertThat(response.isPaymentReceiptAvailable()).isFalse();
        assertThat(response.getPaymentReceiptNumber()).isNull();
        assertThat(response.getInvoiceStatus()).isEqualTo(BillingInvoiceStatus.NOT_ELIGIBLE);
    }

    @Test
    void underpaidOrderIsFlaggedForAccountingReviewWithoutActivatingPlan() {
        BillingOrder order = pendingOrder();
        Map<String, Object> payload = Map.of("signature", "signed");
        when(paymentGateway.verifyWebhook(payload)).thenReturn(
                new PaymentGateway.VerifiedWebhook(100001, 100_000, "VND", "link-1", "bank-ref-1", "00"));
        when(orderRepository.findByOrderCodeForUpdate(100001L)).thenReturn(Optional.of(order));
        when(paymentGateway.getPayment(100001L)).thenReturn(
                new PaymentGateway.ProviderPayment(100001, 500_000, 100_000, "link-1", "PENDING", "bank-ref-1"));

        service.processWebhook(payload);

        assertThat(order.getStatus()).isEqualTo(BillingOrderStatus.UNDERPAID);
        assertThat(order.getInvoiceStatus()).isEqualTo(BillingInvoiceStatus.PAYMENT_REVIEW);
        assertThat(order.getFailureReason()).contains("chưa đủ");
        verifyNoInteractions(subscriptionService);
    }

    private BillingOrder pendingOrder() {
        return BillingOrder.builder()
                .id(UUID.randomUUID())
                .orderCode(100001L)
                .tenantId(UUID.randomUUID())
                .tenantNameSnapshot("Công ty FOFO")
                .planId(UUID.randomUUID())
                .planNameSnapshot("pro")
                .planDisplaySnapshot("Pro")
                .billingCycle(BillingCycle.MONTHLY)
                .amount(500_000L)
                .amountPaid(0L)
                .currency("VND")
                .status(BillingOrderStatus.PENDING)
                .paymentLinkId("link-1")
                .createdBy(UUID.randomUUID())
                .expiresAt(OffsetDateTime.now().plusMinutes(30))
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }
}
