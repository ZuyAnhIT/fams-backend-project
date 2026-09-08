package com.fams.modules.subscription.service;

import com.fams.modules.subscription.dto.request.AssignSubscriptionRequest;
import com.fams.modules.subscription.dto.request.UpdateSubscriptionRequest;
import com.fams.modules.subscription.dto.response.SubscriptionResponse;
import com.fams.modules.subscription.entity.Plan;
import com.fams.modules.subscription.entity.TenantSubscription;
import com.fams.modules.subscription.entity.TenantSubscription.SubscriptionStatus;
import com.fams.modules.subscription.repository.PlanRepository;
import com.fams.modules.subscription.repository.TenantSubscriptionRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.tenant.service.TenantService;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class TenantSubscriptionService {

    private final TenantRepository tenantRepository;
    private final PlanRepository planRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final AuditLogService auditLogService;
    private final TenantService tenantService;

    public TenantSubscriptionService(TenantRepository tenantRepository,
                                     PlanRepository planRepository,
                                     TenantSubscriptionRepository subscriptionRepository,
                                     AuditLogService auditLogService,
                                     TenantService tenantService) {
        this.tenantRepository = tenantRepository;
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.auditLogService = auditLogService;
        this.tenantService = tenantService;
    }

    /** #31 (docs/api/backend-feature-audit-2026-08-07.md): which plan a tenant is on and its
     *  billing status directly drives PlanLimitEnforcementService (how many employees/sites/
     *  random checks that tenant may use) — a change here has real operational impact and was
     *  never traced to an actor. Best-effort, same non-blocking stance as every other call site. */
    private void recordSubscriptionAudit(UUID tenantId, UUID actorId, String action,
                                          Map<String, Object> oldValue, Map<String, Object> newValue) {
        try {
            auditLogService.record(
                    tenantId, actorId, null,
                    "TenantSubscription", tenantId.toString(), action,
                    oldValue, newValue,
                    HttpRequestUtils.currentRequestId(),
                    HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception ex) {
            log.warn("Failed to record audit log for {} tenantId={}: {}", action, tenantId, ex.getMessage());
        }
    }

    private Map<String, Object> subscriptionAuditSnapshot(TenantSubscription sub) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", sub.getPlanId() != null ? sub.getPlanId().toString() : null);
        m.put("status", sub.getStatus() != null ? sub.getStatus().name() : null);
        m.put("billingCycle", sub.getBillingCycle() != null ? sub.getBillingCycle().name() : null);
        m.put("startedAt", sub.getStartedAt() != null ? sub.getStartedAt().toString() : null);
        m.put("expiresAt", sub.getExpiresAt() != null ? sub.getExpiresAt().toString() : null);
        return m;
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getSubscription(UUID tenantId, UUID userId, boolean isPlatformAdmin) {
        assertTenantAccessible(tenantId, userId, isPlatformAdmin);
        TenantSubscription sub = subscriptionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("No subscription found for tenant: " + tenantId));
        Plan plan = getPlan(sub.getPlanId());
        return toResponse(sub, plan);
    }

    @Transactional
    public SubscriptionResponse assignSubscription(UUID tenantId, AssignSubscriptionRequest request, UUID callerUserId) {
        assertTenantExists(tenantId);
        if (subscriptionRepository.existsByTenantId(tenantId)) {
            throw new DuplicateResourceException("Tenant already has a subscription. Use PATCH to update it.");
        }
        Plan plan = getPlan(request.getPlanId());

        TenantSubscription sub = TenantSubscription.builder()
                .tenantId(tenantId)
                .planId(request.getPlanId())
                .billingCycle(request.getBillingCycle())
                .status(request.getStatus() != null ? request.getStatus() : SubscriptionStatus.ACTIVE)
                .startedAt(request.getStartedAt() != null ? request.getStartedAt() : OffsetDateTime.now())
                .expiresAt(request.getExpiresAt())
                .build();

        subscriptionRepository.save(sub);
        log.info("Subscription assigned: tenantId={} planId={}", tenantId, request.getPlanId());
        recordSubscriptionAudit(tenantId, callerUserId, "subscription_assigned", null, subscriptionAuditSnapshot(sub));
        return toResponse(sub, plan);
    }

    @Transactional
    public SubscriptionResponse updateSubscription(UUID tenantId, UpdateSubscriptionRequest request, UUID callerUserId) {
        assertTenantExists(tenantId);
        TenantSubscription sub = subscriptionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("No subscription found for tenant: " + tenantId));

        Map<String, Object> before = subscriptionAuditSnapshot(sub);

        if (request.getPlanId() != null) {
            getPlan(request.getPlanId()); // validate plan exists
            sub.setPlanId(request.getPlanId());
        }
        if (request.getBillingCycle() != null) {
            sub.setBillingCycle(request.getBillingCycle());
        }
        if (request.getStatus() != null) {
            sub.setStatus(request.getStatus());
            if (request.getStatus() == SubscriptionStatus.CANCELLED && sub.getCancelledAt() == null) {
                sub.setCancelledAt(OffsetDateTime.now());
            }
        }
        if (request.getStartedAt() != null) {
            sub.setStartedAt(request.getStartedAt());
        }
        if (request.isClearExpiresAt()) {
            sub.setExpiresAt(null);
        } else if (request.getExpiresAt() != null) {
            sub.setExpiresAt(request.getExpiresAt());
        }

        subscriptionRepository.save(sub);
        log.info("Subscription updated: tenantId={}", tenantId);
        recordSubscriptionAudit(tenantId, callerUserId, "subscription_updated", before, subscriptionAuditSnapshot(sub));
        Plan plan = getPlan(sub.getPlanId());
        return toResponse(sub, plan);
    }

    /**
     * Applies one paid billing period. Repeated delivery of the same webhook is guarded by the
     * billing-order row; this method owns only the subscription period calculation.
     *
     * <p>Renewing the same active plan preserves already-paid remaining time. Changing plan,
     * recovering an expired subscription, or leaving trial starts a fresh period at payment time.
     * Downgrades/proration are deliberately outside the online-payment MVP.</p>
     */
    @Transactional
    public SubscriptionResponse activateFromPayment(UUID tenantId, UUID planId,
                                                      TenantSubscription.BillingCycle billingCycle,
                                                      OffsetDateTime paidAt, UUID billingOrderId) {
        assertTenantExists(tenantId);
        Plan plan = getPlan(planId);
        // The plan is validated as active before checkout is created. A later deactivation must
        // not make a verified, already-created payment lose the service the customer purchased.

        TenantSubscription sub = subscriptionRepository.findByTenantId(tenantId).orElse(null);
        Map<String, Object> before = sub != null ? subscriptionAuditSnapshot(sub) : null;
        boolean renewableSamePlan = sub != null
                && planId.equals(sub.getPlanId())
                && sub.getStatus() == SubscriptionStatus.ACTIVE
                && sub.getExpiresAt() != null
                && sub.getExpiresAt().isAfter(paidAt);
        OffsetDateTime periodStart = renewableSamePlan ? sub.getExpiresAt() : paidAt;
        OffsetDateTime periodEnd = billingCycle == TenantSubscription.BillingCycle.YEARLY
                ? periodStart.plusYears(1)
                : periodStart.plusMonths(1);

        if (sub == null) {
            sub = TenantSubscription.builder()
                    .tenantId(tenantId)
                    .planId(planId)
                    .startedAt(paidAt)
                    .build();
        } else if (!renewableSamePlan) {
            sub.setStartedAt(paidAt);
        }
        sub.setPlanId(planId);
        sub.setBillingCycle(billingCycle);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setExpiresAt(periodEnd);
        sub.setCancelledAt(null);
        subscriptionRepository.save(sub);

        Map<String, Object> after = subscriptionAuditSnapshot(sub);
        after.put("billingOrderId", billingOrderId.toString());
        recordSubscriptionAudit(tenantId, null, "subscription_payment_activated", before, after);
        tenantService.activateTenantAfterPayment(tenantId);
        return toResponse(sub, plan);
    }

    private void assertTenantExists(UUID tenantId) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
    }

    private void assertTenantAccessible(UUID tenantId, UUID userId, boolean isPlatformAdmin) {
        var tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
        if (!isPlatformAdmin && !userId.equals(tenant.getOwnerId())) {
            throw new AccessDeniedException("You do not have permission to access this tenant's subscription");
        }
    }

    private Plan getPlan(UUID planId) {
        return planRepository.findByIdAndDeletedAtIsNull(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + planId));
    }

    private SubscriptionResponse toResponse(TenantSubscription sub, Plan plan) {
        return SubscriptionResponse.builder()
                .id(sub.getId())
                .tenantId(sub.getTenantId())
                .planId(sub.getPlanId())
                .planName(plan.getName())
                .planDisplayName(plan.getDisplayName())
                .status(sub.getStatus())
                .billingCycle(sub.getBillingCycle())
                .startedAt(sub.getStartedAt())
                .expiresAt(sub.getExpiresAt())
                .cancelledAt(sub.getCancelledAt())
                .createdAt(sub.getCreatedAt())
                .updatedAt(sub.getUpdatedAt())
                .build();
    }
}
