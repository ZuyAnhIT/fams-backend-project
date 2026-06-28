package com.fams.modules.assignment.repository;

import com.fams.modules.assignment.entity.Assignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID>,
        JpaSpecificationExecutor<Assignment> {

    boolean existsByEmployeeIdAndSiteIdAndStatusAndDeletedAtIsNull(
            UUID employeeId, UUID siteId, String status);

    Optional<Assignment> findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(
            UUID id, UUID siteId, UUID tenantId);

    Page<Assignment> findBySiteIdAndTenantIdAndDeletedAtIsNull(
            UUID siteId, UUID tenantId, Pageable pageable);

    long countBySiteIdAndStatusAndDeletedAtIsNull(UUID siteId, String status);

    @Query("SELECT a FROM Assignment a WHERE a.tenantId = :tenantId AND a.employeeId = :employeeId " +
           "AND a.status = 'active' AND a.deletedAt IS NULL " +
           "AND a.startDate <= :today AND (a.endDate IS NULL OR a.endDate >= :today)")
    List<Assignment> findActiveAssignmentsForEmployeeOnDate(
            @Param("tenantId") UUID tenantId,
            @Param("employeeId") UUID employeeId,
            @Param("today") LocalDate today);

    @Query("SELECT a FROM Assignment a WHERE a.tenantId = :tenantId AND a.status = 'active' " +
           "AND a.deletedAt IS NULL AND a.shiftId IS NOT NULL " +
           "AND a.startDate <= :date AND (a.endDate IS NULL OR a.endDate >= :date)")
    List<Assignment> findActiveAssignmentsWithShiftForDate(
            @Param("tenantId") UUID tenantId,
            @Param("date") LocalDate date);

    @Query("SELECT a FROM Assignment a WHERE a.status = 'active' AND a.deletedAt IS NULL " +
           "AND a.shiftId IS NOT NULL " +
           "AND a.startDate <= :date AND (a.endDate IS NULL OR a.endDate >= :date)")
    List<Assignment> findAllActiveAssignmentsWithShiftForDate(@Param("date") LocalDate date);

    @Query("SELECT a FROM Assignment a WHERE a.tenantId = :tenantId AND a.siteId = :siteId " +
           "AND a.employeeId = :employeeId AND a.status = 'active' AND a.deletedAt IS NULL " +
           "AND a.startDate <= :today AND (a.endDate IS NULL OR a.endDate >= :today)")
    Optional<Assignment> findActiveAssignmentByEmployeeAndSite(
            @Param("tenantId") UUID tenantId,
            @Param("siteId") UUID siteId,
            @Param("employeeId") UUID employeeId,
            @Param("today") LocalDate today);
}
