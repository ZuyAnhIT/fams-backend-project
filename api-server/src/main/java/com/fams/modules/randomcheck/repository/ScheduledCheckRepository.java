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

    /**
     * Deliberately bounded by scheduledAt <= :cutoff — a 'pending' (not yet dispatched) check
     * carries its full future scheduledAt, and the whole point of "random" is that the employee
     * doesn't get to see the schedule in advance. Found via FE security audit (2026-08-01): this
     * endpoint previously had no time bound at all, so an employee could call it right after the
     * daily generation job ran and read off every check time planned for the rest of the day —
     * network-inspectable even though the app itself only displayed imminent ones client-side.
     * Callers should pass a tight cutoff (e.g. now + dispatch poll interval) so a check only
     * becomes visible once it's genuinely about to fire, not meaningfully earlier than dispatch
     * would have surfaced it anyway.
     */
    @Query("SELECT s FROM ScheduledCheck s WHERE s.tenantId = :tenantId " +
           "AND s.employeeId = :employeeId AND s.status = 'pending' " +
           "AND s.scheduledAt <= :cutoff " +
           "AND s.deletedAt IS NULL ORDER BY s.scheduledAt")
    List<ScheduledCheck> findPendingForEmployeeDueSoon(@Param("tenantId") UUID tenantId,
                                                       @Param("employeeId") UUID employeeId,
                                                       @Param("cutoff") java.time.OffsetDateTime cutoff);

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

    /** Every 'pending' (not yet dispatched) check, regardless of tenant — used only by
     *  RandomCheckQueueReconciliationRunner on app startup to re-populate the Redis dispatch
     *  queue (fams:randomcheck:dispatch), since that ZSET has no other source of truth and isn't
     *  reconstructed from anywhere else. Found via audit (2026-08-01): an app restart, or a Redis
     *  key eviction under memory pressure (maxmemory-policy=allkeys-lru), silently dropped
     *  already-generated checks out of the dispatch queue with no self-healing — they'd never
     *  get their "sent" notification, just eventually time out as no_response. */
    @Query("SELECT s FROM ScheduledCheck s WHERE s.status = 'pending' AND s.deletedAt IS NULL")
    List<ScheduledCheck> findAllPendingForQueueReconciliation();

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
