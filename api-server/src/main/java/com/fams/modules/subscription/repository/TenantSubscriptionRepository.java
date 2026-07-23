package com.fams.modules.subscription.repository;

import com.fams.modules.subscription.entity.TenantSubscription;
import com.fams.modules.subscription.entity.TenantSubscription.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscription, UUID> {
    Optional<TenantSubscription> findByTenantId(UUID tenantId);
    boolean existsByTenantId(UUID tenantId);
    List<TenantSubscription> findAllByStatusAndExpiresAtBefore(SubscriptionStatus status, OffsetDateTime cutoff);

    /** Issue #8 (docs/issues/ISSUES.md): tenants still subscribed to a plan being deactivated. */
    List<TenantSubscription> findAllByPlanIdAndStatusIn(UUID planId, List<SubscriptionStatus> statuses);
}
