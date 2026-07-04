package com.fams.modules.violation.repository;

import com.fams.modules.violation.entity.Violation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ViolationRepository extends JpaRepository<Violation, UUID>, JpaSpecificationExecutor<Violation> {

    boolean existsByScheduledCheckIdAndViolationType(UUID scheduledCheckId, String violationType);

    java.util.Optional<Violation> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    @Query("SELECT COUNT(v) FROM Violation v WHERE v.tenantId = :tenantId AND v.resolved = false AND v.deletedAt IS NULL")
    long countUnresolved(@Param("tenantId") UUID tenantId);

    @Query("SELECT v.violationType, COUNT(v) FROM Violation v WHERE v.tenantId = :tenantId AND v.resolved = false AND v.deletedAt IS NULL GROUP BY v.violationType")
    List<Object[]> countUnresolvedByType(@Param("tenantId") UUID tenantId);

    @Query("SELECT COUNT(v) FROM Violation v WHERE v.tenantId = :tenantId AND v.resolved = true AND v.resolvedAt >= :since AND v.deletedAt IS NULL")
    long countResolvedSince(@Param("tenantId") UUID tenantId, @Param("since") OffsetDateTime since);

    @Query("SELECT v FROM Violation v WHERE v.tenantId = :tenantId AND v.deletedAt IS NULL " +
           "ORDER BY v.createdAt DESC")
    List<Violation> findByTenant(@Param("tenantId") UUID tenantId);

    @Query("SELECT v FROM Violation v WHERE v.tenantId = :tenantId AND v.checkDate = :date " +
           "AND v.deletedAt IS NULL ORDER BY v.createdAt DESC")
    List<Violation> findByTenantAndDate(@Param("tenantId") UUID tenantId,
                                        @Param("date") LocalDate date);
}
