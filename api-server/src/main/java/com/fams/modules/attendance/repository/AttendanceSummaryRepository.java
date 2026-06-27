package com.fams.modules.attendance.repository;

import com.fams.modules.attendance.entity.AttendanceSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceSummaryRepository
        extends JpaRepository<AttendanceSummary, UUID>, JpaSpecificationExecutor<AttendanceSummary> {

    Optional<AttendanceSummary> findByTenantIdAndEmployeeIdAndSiteIdAndAttendanceDateAndDeletedAtIsNull(
            UUID tenantId, UUID employeeId, UUID siteId, LocalDate attendanceDate);

    Optional<AttendanceSummary> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    @Query("SELECT a FROM AttendanceSummary a WHERE a.tenantId = :tenantId AND a.employeeId = :employeeId " +
           "AND a.deletedAt IS NULL ORDER BY a.attendanceDate DESC")
    java.util.List<AttendanceSummary> findByTenantIdAndEmployeeIdOrderByDateDesc(
            @Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Query("SELECT a FROM AttendanceSummary a WHERE a.tenantId = :tenantId AND a.employeeId = :employeeId " +
           "AND a.attendanceDate >= :from AND a.attendanceDate < :to AND a.deletedAt IS NULL " +
           "ORDER BY a.attendanceDate ASC")
    java.util.List<AttendanceSummary> findByTenantIdAndEmployeeIdAndDateRange(
            @Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
