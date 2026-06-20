package com.fams.modules.tenant.repository;

import com.fams.modules.tenant.entity.TenantIpWhitelist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantIpWhitelistRepository extends JpaRepository<TenantIpWhitelist, UUID> {

    List<TenantIpWhitelist> findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID tenantId);

    Optional<TenantIpWhitelist> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
}
