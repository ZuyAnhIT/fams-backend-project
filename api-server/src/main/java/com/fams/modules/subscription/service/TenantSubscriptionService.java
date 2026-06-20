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
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
public class TenantSubscriptionService {

    private final TenantRepository tenantRepository;
    private final PlanRepository planRepository;
    private final TenantSubscriptionRepository subscriptionRepository;

    public TenantSubscriptionService(TenantRepository tenantRepository,
                                     PlanRepository planRepository,
                                     TenantSubscriptionRepository subscriptionRepository) {
        this.tenantRepository = tenantRepository;
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
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
    public SubscriptionResponse assignSubscription(UUID tenantId, AssignSubscriptionRequest request) {
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
        return toResponse(sub, plan);
    }

    @Transactional
    public SubscriptionResponse updateSubscription(UUID tenantId, UpdateSubscriptionRequest request) {
        assertTenantExists(tenantId);
        TenantSubscription sub = subscriptionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("No subscription found for tenant: " + tenantId));

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
        Plan plan = getPlan(sub.getPlanId());
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
