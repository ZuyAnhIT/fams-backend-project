package com.fams.modules.tenant.repository;

import com.fams.modules.tenant.entity.TenantSettings;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TenantSettingsRepository extends JpaRepository<TenantSettings, UUID> {

    Optional<TenantSettings> findByTenantId(UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM TenantSettings s WHERE s.tenantId = :tenantId")
    Optional<TenantSettings> findByTenantIdForUpdate(@Param("tenantId") UUID tenantId);
}
