package com.fams.modules.randomcheck.repository;

import com.fams.modules.randomcheck.entity.RandomCheckConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RandomCheckConfigRepository extends JpaRepository<RandomCheckConfig, UUID> {

    @Query("SELECT c FROM RandomCheckConfig c WHERE c.tenantId = :tenantId AND c.siteId IS NULL AND c.deletedAt IS NULL")
    Optional<RandomCheckConfig> findTenantDefault(@Param("tenantId") UUID tenantId);

    @Query("SELECT c FROM RandomCheckConfig c WHERE c.tenantId = :tenantId AND c.siteId = :siteId AND c.deletedAt IS NULL")
    Optional<RandomCheckConfig> findBySite(@Param("tenantId") UUID tenantId, @Param("siteId") UUID siteId);

    @Query("SELECT c FROM RandomCheckConfig c WHERE c.tenantId = :tenantId AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<RandomCheckConfig> findAllByTenant(@Param("tenantId") UUID tenantId);

    @Query("SELECT c FROM RandomCheckConfig c WHERE c.id = :id AND c.tenantId = :tenantId AND c.deletedAt IS NULL")
    Optional<RandomCheckConfig> findByIdAndTenant(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    boolean existsByTenantIdAndSiteIdIsNullAndDeletedAtIsNull(UUID tenantId);
}
