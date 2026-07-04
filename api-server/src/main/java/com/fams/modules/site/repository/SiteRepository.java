package com.fams.modules.site.repository;

import com.fams.modules.site.entity.Site;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SiteRepository extends JpaRepository<Site, UUID>, JpaSpecificationExecutor<Site> {

    Optional<Site> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    @Query("SELECT COUNT(s) > 0 FROM Site s WHERE s.tenantId = :tenantId " +
           "AND lower(s.name) = lower(:name) AND s.deletedAt IS NULL")
    boolean existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(
            @Param("tenantId") UUID tenantId, @Param("name") String name);

    @Query("SELECT COUNT(s) > 0 FROM Site s WHERE s.tenantId = :tenantId " +
           "AND lower(s.name) = lower(:name) AND s.deletedAt IS NULL AND s.id <> :excludeId")
    boolean existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNullAndIdNot(
            @Param("tenantId") UUID tenantId, @Param("name") String name, @Param("excludeId") UUID excludeId);

    boolean existsByTenantIdAndCodeAndDeletedAtIsNull(UUID tenantId, String code);

    boolean existsByTenantIdAndCodeAndDeletedAtIsNullAndIdNot(UUID tenantId, String code, UUID excludeId);

    long countByTenantIdAndDeletedAtIsNull(UUID tenantId);
}
