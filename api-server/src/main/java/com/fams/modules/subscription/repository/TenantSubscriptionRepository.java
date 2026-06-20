package com.fams.modules.subscription.repository;

import com.fams.modules.subscription.entity.TenantSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscription, UUID> {
    Optional<TenantSubscription> findByTenantId(UUID tenantId);
    boolean existsByTenantId(UUID tenantId);
}
