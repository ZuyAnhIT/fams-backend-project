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

    Optional<Assignment> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    Page<Assignment> findBySiteIdAndTenantIdAndDeletedAtIsNull(
            UUID siteId, UUID tenantId, Pageable pageable);

    long countBySiteIdAndStatusAndDeletedAtIsNull(UUID siteId, String status);

    // Issue #14 (docs/issues/ISSUES.md): the 6 date-scoped "is this assignment active on this
    // exact date" queries below are native SQL (not JPQL) because they need Postgres's bitwise
    // `&` operator to test the days_of_week bitmask against the query date's day-of-week bit
    // (:dayBit, computed in Java via DayOfWeekBitmask.bitForDate) — JPQL has no bitwise operator.
    // days_of_week IS NULL still means "every day", preserving exact prior behavior for every
    // assignment that doesn't set it.

    @Query(value = "SELECT * FROM assignments a WHERE a.tenant_id = :tenantId AND a.employee_id = :employeeId " +
           "AND a.status = 'active' AND a.deleted_at IS NULL " +
           "AND a.start_date <= :today AND (a.end_date IS NULL OR a.end_date >= :today) " +
           "AND (a.days_of_week IS NULL OR (a.days_of_week & :dayBit) <> 0)", nativeQuery = true)
    List<Assignment> findActiveAssignmentsForEmployeeOnDate(
            @Param("tenantId") UUID tenantId,
            @Param("employeeId") UUID employeeId,
            @Param("today") LocalDate today,
            @Param("dayBit") int dayBit);

    @Query(value = "SELECT * FROM assignments a WHERE a.tenant_id = :tenantId AND a.status = 'active' " +
           "AND a.deleted_at IS NULL AND a.shift_id IS NOT NULL " +
           "AND a.start_date <= :date AND (a.end_date IS NULL OR a.end_date >= :date) " +
           "AND (a.days_of_week IS NULL OR (a.days_of_week & :dayBit) <> 0)", nativeQuery = true)
    List<Assignment> findActiveAssignmentsWithShiftForDate(
            @Param("tenantId") UUID tenantId,
            @Param("date") LocalDate date,
            @Param("dayBit") int dayBit);

    @Query(value = "SELECT * FROM assignments a WHERE a.status = 'active' AND a.deleted_at IS NULL " +
           "AND a.shift_id IS NOT NULL " +
           "AND a.start_date <= :date AND (a.end_date IS NULL OR a.end_date >= :date) " +
           "AND (a.days_of_week IS NULL OR (a.days_of_week & :dayBit) <> 0)", nativeQuery = true)
    List<Assignment> findAllActiveAssignmentsWithShiftForDate(
            @Param("date") LocalDate date,
            @Param("dayBit") int dayBit);

    @Query(value = "SELECT * FROM assignments a WHERE a.tenant_id = :tenantId AND a.site_id = :siteId " +
           "AND a.employee_id = :employeeId AND a.status = 'active' AND a.deleted_at IS NULL " +
           "AND a.start_date <= :today AND (a.end_date IS NULL OR a.end_date >= :today) " +
           "AND (a.days_of_week IS NULL OR (a.days_of_week & :dayBit) <> 0)", nativeQuery = true)
    Optional<Assignment> findActiveAssignmentByEmployeeAndSite(
            @Param("tenantId") UUID tenantId,
            @Param("siteId") UUID siteId,
            @Param("employeeId") UUID employeeId,
            @Param("today") LocalDate today,
            @Param("dayBit") int dayBit);

    @Query(value = "SELECT * FROM assignments a WHERE a.tenant_id = :tenantId AND a.employee_id = :employeeId " +
           "AND a.role = 'supervisor' AND a.status = 'active' AND a.deleted_at IS NULL " +
           "AND a.start_date <= :today AND (a.end_date IS NULL OR a.end_date >= :today) " +
           "AND (a.days_of_week IS NULL OR (a.days_of_week & :dayBit) <> 0)", nativeQuery = true)
    List<Assignment> findActiveSupervisorAssignmentsForEmployee(
            @Param("tenantId") UUID tenantId,
            @Param("employeeId") UUID employeeId,
            @Param("today") LocalDate today,
            @Param("dayBit") int dayBit);

    @Query(value = "SELECT * FROM assignments a WHERE a.tenant_id = :tenantId AND a.site_id = :siteId " +
           "AND a.status = 'active' AND a.deleted_at IS NULL " +
           "AND a.start_date <= :today AND (a.end_date IS NULL OR a.end_date >= :today) " +
           "AND (a.days_of_week IS NULL OR (a.days_of_week & :dayBit) <> 0)", nativeQuery = true)
    List<Assignment> findActiveAssignmentsForSiteOnDate(
            @Param("tenantId") UUID tenantId,
            @Param("siteId") UUID siteId,
            @Param("today") LocalDate today,
            @Param("dayBit") int dayBit);
}
