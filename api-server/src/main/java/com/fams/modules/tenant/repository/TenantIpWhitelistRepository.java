package com.fams.modules.tenant.repository;

import com.fams.modules.tenant.entity.TenantIpWhitelist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantIpWhitelistRepository extends JpaRepository<TenantIpWhitelist, UUID> {

    List<TenantIpWhitelist> findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID tenantId);

    List<TenantIpWhitelist> findByTenantIdAndIsActiveTrueAndDeletedAtIsNull(UUID tenantId);

    Page<TenantIpWhitelist> findByTenantIdAndDeletedAtIsNull(UUID tenantId, Pageable pageable);

    Optional<TenantIpWhitelist> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
}
