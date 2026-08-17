package com.fams.modules.attendance.repository;

import com.fams.modules.attendance.entity.AttendanceSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** #85 (2026-08-17): same as {@link #findByTenantIdAndEmployeeIdAndDateRange} but with an
     *  optional site filter — an employee working multiple sites can narrow "my monthly
     *  attendance" down to one site instead of always seeing every site merged together. */
    @Query("SELECT a FROM AttendanceSummary a WHERE a.tenantId = :tenantId AND a.employeeId = :employeeId " +
           "AND a.attendanceDate >= :from AND a.attendanceDate < :to AND a.deletedAt IS NULL " +
           "AND (:siteId IS NULL OR a.siteId = :siteId) " +
           "ORDER BY a.attendanceDate ASC")
    java.util.List<AttendanceSummary> findByTenantIdAndEmployeeIdAndSiteIdAndDateRange(
            @Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
            @Param("siteId") UUID siteId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(a) FROM AttendanceSummary a WHERE a.tenantId = :tenantId AND a.attendanceDate = :date AND a.deletedAt IS NULL")
    long countByTenantAndDate(@Param("tenantId") UUID tenantId, @Param("date") LocalDate date);

    @Query("SELECT COUNT(a) FROM AttendanceSummary a WHERE a.tenantId = :tenantId AND a.attendanceDate = :date AND a.late = true AND a.deletedAt IS NULL")
    long countLateByTenantAndDate(@Param("tenantId") UUID tenantId, @Param("date") LocalDate date);

    /**
     * HR monthly aggregate (one row per employee+site), grouped and paginated at the DB level —
     * replaces the previous approach of loading every daily row for the whole tenant/month into
     * Java and grouping/paging in memory (a real scaling concern flagged in an audit, 2026-07-31,
     * see docs/api/attendance-management-api.md). `employeeId`/`siteId` are nullable filters
     * (pass null to mean "no filter on this dimension").
     *
     * #86 (2026-08-17): added `status` (HAVING filter — keep an employee+site+month row only if
     * AT LEAST ONE day in that month has the given daily status, e.g. "incomplete") and
     * `sortBy`/`sortDir` (whitelisted via CASE branches, NOT string-concatenated — safe against
     * injection since :sortBy/:sortDir are still bind params, just compared rather than
     * interpolated). Unmatched/null sortBy falls through to the original stable
     * employee_id,site_id order.
     */
    @Query(value = """
            SELECT employee_id AS employeeId, site_id AS siteId,
                   COUNT(*) AS presentDays,
                   COALESCE(SUM(total_work_minutes), 0) AS totalWorkMinutes,
                   COALESCE(SUM(CASE WHEN is_late THEN 1 ELSE 0 END), 0) AS lateDays,
                   COALESCE(SUM(late_minutes), 0) AS totalLateMinutes,
                   COALESCE(SUM(CASE WHEN is_early_leave THEN 1 ELSE 0 END), 0) AS earlyLeaveDays,
                   COALESCE(SUM(early_leave_minutes), 0) AS totalEarlyLeaveMinutes,
                   COALESCE(SUM(ot_minutes), 0) AS totalOtMinutes,
                   COALESCE(SUM(CASE WHEN missing_checkout THEN 1 ELSE 0 END), 0) AS missingCheckoutDays,
                   COALESCE(SUM(CASE WHEN has_pending_review_session THEN 1 ELSE 0 END), 0) AS daysWithPendingReview,
                   COALESCE(SUM(CASE WHEN has_rejected_session THEN 1 ELSE 0 END), 0) AS daysWithRejectedSession,
                   COALESCE(SUM(CASE WHEN has_random_check_failure THEN 1 ELSE 0 END), 0) AS daysWithRandomCheckFailure
            FROM attendance_summaries
            WHERE tenant_id = :tenantId AND deleted_at IS NULL
              AND attendance_date >= :from AND attendance_date < :to
              AND (CAST(:employeeId AS uuid) IS NULL OR employee_id = :employeeId)
              AND (CAST(:siteId AS uuid) IS NULL OR site_id = :siteId)
            GROUP BY employee_id, site_id
            HAVING (CAST(:status AS text) IS NULL OR SUM(CASE WHEN status = :status THEN 1 ELSE 0 END) > 0)
            ORDER BY
              CASE WHEN :sortBy = 'totalWorkMinutes' AND :sortDir = 'asc' THEN SUM(total_work_minutes) END ASC NULLS LAST,
              CASE WHEN :sortBy = 'totalWorkMinutes' AND :sortDir = 'desc' THEN SUM(total_work_minutes) END DESC NULLS LAST,
              CASE WHEN :sortBy = 'totalLateMinutes' AND :sortDir = 'asc' THEN SUM(late_minutes) END ASC NULLS LAST,
              CASE WHEN :sortBy = 'totalLateMinutes' AND :sortDir = 'desc' THEN SUM(late_minutes) END DESC NULLS LAST,
              CASE WHEN :sortBy = 'totalOtMinutes' AND :sortDir = 'asc' THEN SUM(ot_minutes) END ASC NULLS LAST,
              CASE WHEN :sortBy = 'totalOtMinutes' AND :sortDir = 'desc' THEN SUM(ot_minutes) END DESC NULLS LAST,
              CASE WHEN :sortBy = 'missingCheckoutDays' AND :sortDir = 'asc' THEN SUM(CASE WHEN missing_checkout THEN 1 ELSE 0 END) END ASC NULLS LAST,
              CASE WHEN :sortBy = 'missingCheckoutDays' AND :sortDir = 'desc' THEN SUM(CASE WHEN missing_checkout THEN 1 ELSE 0 END) END DESC NULLS LAST,
              CASE WHEN :sortBy = 'presentDays' AND :sortDir = 'asc' THEN COUNT(*) END ASC NULLS LAST,
              CASE WHEN :sortBy = 'presentDays' AND :sortDir = 'desc' THEN COUNT(*) END DESC NULLS LAST,
              employee_id, site_id
            """,
            countQuery = """
            SELECT COUNT(*) FROM (
                SELECT 1 FROM attendance_summaries
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                  AND attendance_date >= :from AND attendance_date < :to
                  AND (CAST(:employeeId AS uuid) IS NULL OR employee_id = :employeeId)
                  AND (CAST(:siteId AS uuid) IS NULL OR site_id = :siteId)
                GROUP BY employee_id, site_id
                HAVING (CAST(:status AS text) IS NULL OR SUM(CASE WHEN status = :status THEN 1 ELSE 0 END) > 0)
            ) grouped
            """,
            nativeQuery = true)
    Page<AttendanceMonthlyAggregateProjection> aggregateMonthly(
            @Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
            @Param("siteId") UUID siteId, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("status") String status, @Param("sortBy") String sortBy, @Param("sortDir") String sortDir,
            Pageable pageable);
}
