package com.fams.modules.randomcheck.repository;

import com.fams.modules.randomcheck.entity.ScheduledCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ScheduledCheckRepository extends JpaRepository<ScheduledCheck, UUID> {

    boolean existsByAssignmentIdAndCheckDateAndDeletedAtIsNull(UUID assignmentId, LocalDate checkDate);

    /** Used by AttendanceSummaryService.recompute() to set hasRandomCheckFailure — true if this
     *  employee/site/date had >=1 random check that ended in no_response, or was responded to
     *  with outcome='fail' (location/face/liveness). Read-only signal, never touches pay fields. */
    @Query(value = "SELECT EXISTS (" +
                  "SELECT 1 FROM scheduled_checks sc " +
                  "LEFT JOIN check_responses cr ON cr.scheduled_check_id = sc.id " +
                  "WHERE sc.tenant_id = :tenantId AND sc.employee_id = :employeeId " +
                  "AND sc.site_id = :siteId AND sc.check_date = :date AND sc.deleted_at IS NULL " +
                  "AND (sc.status = 'no_response' OR cr.outcome = 'fail'))",
           nativeQuery = true)
    boolean existsFailedOrNoResponseCheck(@Param("tenantId") UUID tenantId,
                                          @Param("employeeId") UUID employeeId,
                                          @Param("siteId") UUID siteId,
                                          @Param("date") LocalDate date);

    boolean existsByAssignmentIdAndCheckDateAndCheckIndex(UUID assignmentId, LocalDate checkDate, int checkIndex);

    @Query("SELECT s FROM ScheduledCheck s WHERE s.tenantId = :tenantId AND s.checkDate = :date " +
           "AND s.deletedAt IS NULL ORDER BY s.scheduledAt")
    List<ScheduledCheck> findByTenantAndDate(@Param("tenantId") UUID tenantId, @Param("date") LocalDate date);

    @Query("SELECT s FROM ScheduledCheck s WHERE s.tenantId = :tenantId " +
           "AND s.employeeId = :employeeId AND s.status = 'pending' " +
           "AND s.deletedAt IS NULL ORDER BY s.scheduledAt")
    List<ScheduledCheck> findPendingForEmployee(@Param("tenantId") UUID tenantId,
                                                @Param("employeeId") UUID employeeId);

    @Query("SELECT s FROM ScheduledCheck s WHERE s.assignmentId = :assignmentId " +
           "AND s.checkDate = :date AND s.deletedAt IS NULL ORDER BY s.checkIndex")
    List<ScheduledCheck> findByAssignmentAndDate(@Param("assignmentId") UUID assignmentId,
                                                 @Param("date") LocalDate date);

    @Query("SELECT s FROM ScheduledCheck s WHERE s.assignmentId = :assignmentId " +
           "AND s.status IN ('pending', 'sent') AND s.deletedAt IS NULL")
    List<ScheduledCheck> findCancellableByAssignment(@Param("assignmentId") UUID assignmentId);

    @Query("SELECT s FROM ScheduledCheck s WHERE s.tenantId = :tenantId AND s.id = :id " +
           "AND s.deletedAt IS NULL")
    java.util.Optional<ScheduledCheck> findByIdAndTenant(@Param("id") UUID id,
                                                          @Param("tenantId") UUID tenantId);

    @Query(value = "SELECT s FROM ScheduledCheck s WHERE s.tenantId = :tenantId " +
                  "AND s.deletedAt IS NULL " +
                  "AND (:siteId IS NULL OR s.siteId = :siteId) " +
                  "AND (:employeeId IS NULL OR s.employeeId = :employeeId) " +
                  "AND (:status IS NULL OR s.status = :status) " +
                  "AND s.checkDate >= :dateFrom " +
                  "AND s.checkDate <= :dateTo " +
                  "ORDER BY s.checkDate DESC, s.scheduledAt DESC",
           countQuery = "SELECT COUNT(s) FROM ScheduledCheck s WHERE s.tenantId = :tenantId " +
                        "AND s.deletedAt IS NULL " +
                        "AND (:siteId IS NULL OR s.siteId = :siteId) " +
                        "AND (:employeeId IS NULL OR s.employeeId = :employeeId) " +
                        "AND (:status IS NULL OR s.status = :status) " +
                        "AND s.checkDate >= :dateFrom " +
                        "AND s.checkDate <= :dateTo")
    org.springframework.data.domain.Page<ScheduledCheck> findByTenantWithFilters(
            @Param("tenantId") UUID tenantId,
            @Param("siteId") UUID siteId,
            @Param("employeeId") UUID employeeId,
            @Param("status") String status,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            org.springframework.data.domain.Pageable pageable);

    @Query("SELECT s.status, COUNT(s) FROM ScheduledCheck s WHERE s.tenantId = :tenantId " +
           "AND s.deletedAt IS NULL " +
           "AND s.checkDate >= :dateFrom " +
           "AND s.checkDate <= :dateTo " +
           "AND (:siteId IS NULL OR s.siteId = :siteId) " +
           "GROUP BY s.status")
    List<Object[]> countByStatusGrouped(
            @Param("tenantId") UUID tenantId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("siteId") UUID siteId);

    /** Returns all 'sent' checks whose expires_at is in the past (global, across all tenants). */
    @Query("SELECT s FROM ScheduledCheck s WHERE s.status = 'sent' " +
           "AND s.expiresAt < :now AND s.deletedAt IS NULL")
    List<ScheduledCheck> findExpiredSentChecks(@Param("now") java.time.OffsetDateTime now);

    /** Returns all 'sent' checks whose expires_at is in the past for a single tenant. */
    @Query("SELECT s FROM ScheduledCheck s WHERE s.tenantId = :tenantId AND s.status = 'sent' " +
           "AND s.expiresAt < :now AND s.deletedAt IS NULL")
    List<ScheduledCheck> findExpiredSentChecksForTenant(@Param("tenantId") UUID tenantId,
                                                        @Param("now") java.time.OffsetDateTime now);

    @Query("SELECT COUNT(s) FROM ScheduledCheck s WHERE s.tenantId = :tenantId " +
           "AND s.checkDate >= :monthStart AND s.checkDate <= :monthEnd " +
           "AND s.deletedAt IS NULL")
    long countByTenantAndMonth(@Param("tenantId") UUID tenantId,
                               @Param("monthStart") LocalDate monthStart,
                               @Param("monthEnd") LocalDate monthEnd);
}
