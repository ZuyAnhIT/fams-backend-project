package com.fams.modules.tenant.repository;

import com.fams.modules.tenant.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID>, JpaSpecificationExecutor<Tenant> {

    Optional<Tenant> findByIdAndDeletedAtIsNull(UUID id);

    Optional<Tenant> findBySlugAndDeletedAtIsNull(String slug);

    Optional<Tenant> findByDomainAndDeletedAtIsNull(String domain);
}
