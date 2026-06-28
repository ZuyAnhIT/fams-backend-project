package com.fams.modules.violation.repository;

import com.fams.modules.violation.entity.Violation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ViolationRepository extends JpaRepository<Violation, UUID> {

    boolean existsByScheduledCheckIdAndViolationType(UUID scheduledCheckId, String violationType);

    @Query("SELECT v FROM Violation v WHERE v.tenantId = :tenantId AND v.deletedAt IS NULL " +
           "ORDER BY v.createdAt DESC")
    List<Violation> findByTenant(@Param("tenantId") UUID tenantId);

    @Query("SELECT v FROM Violation v WHERE v.tenantId = :tenantId AND v.checkDate = :date " +
           "AND v.deletedAt IS NULL ORDER BY v.createdAt DESC")
    List<Violation> findByTenantAndDate(@Param("tenantId") UUID tenantId,
                                        @Param("date") LocalDate date);
}
