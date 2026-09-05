package com.fams.modules.checkin.repository;

import com.fams.modules.checkin.entity.CheckinRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface CheckinRepository extends JpaRepository<CheckinRecord, UUID>, JpaSpecificationExecutor<CheckinRecord> {

    /** Open session = no checkout evidence and no logical missing-checkout closure. */
    Optional<CheckinRecord> findByAssignmentIdAndCheckOutAtIsNullAndSessionClosedAtIsNullAndDeletedAtIsNull(
            UUID assignmentId);

    /** An employee physically cannot be checked in at two places at once, even across different
     *  assignments/sites — used to block a new check-in while any prior session (any site) is
     *  still open, instead of only checking the one assignment being checked into. */
    Optional<CheckinRecord> findByTenantIdAndEmployeeIdAndCheckOutAtIsNullAndSessionClosedAtIsNullAndDeletedAtIsNull(
            UUID tenantId, UUID employeeId);

    /** Bounded oldest-first batch used by the proactive expiration job. */
    @Query("SELECT c FROM CheckinRecord c WHERE c.checkOutAt IS NULL AND c.sessionClosedAt IS NULL " +
           "AND c.sessionExpiresAt IS NOT NULL AND c.sessionExpiresAt <= :now AND c.deletedAt IS NULL " +
           "ORDER BY c.sessionExpiresAt ASC")
    List<CheckinRecord> findDueOpenSessions(@Param("now") OffsetDateTime now, Pageable pageable);

    /** Atomically closes a forgotten checkout without racing a real checkout submitted at the
     *  same instant. A full-entity save here could overwrite checkout evidence written by the
     *  employee between the scheduler's SELECT and UPDATE. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CheckinRecord c SET c.sessionClosedAt = :closedAt, " +
           "c.sessionCloseReason = 'missing_checkout' WHERE c.id = :id " +
           "AND c.checkOutAt IS NULL AND c.sessionClosedAt IS NULL")
    int closeAsMissingCheckoutIfOpen(@Param("id") UUID id,
                                      @Param("closedAt") OffsetDateTime closedAt);


    Optional<CheckinRecord> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    /**
     * PostGIS point-in-buffered-polygon check.
     * polygonWkt must be a valid WKT POLYGON string, e.g. POLYGON((lon1 lat1, lon2 lat2, ...)).
     * bufferMeters is applied as a geodetic buffer (ST_Buffer on geography) for accurate metre-based expansion.
     */
    /** All sessions for a given employee/site within a UTC time range, ordered by check-in time. */
    @Query("SELECT c FROM CheckinRecord c WHERE c.tenantId = :tenantId AND c.employeeId = :employeeId " +
           "AND c.siteId = :siteId AND c.deletedAt IS NULL " +
           "AND c.checkInAt >= :from AND c.checkInAt < :to ORDER BY c.checkInAt ASC")
    List<CheckinRecord> findSessionsForDateRange(@Param("tenantId") UUID tenantId,
                                                  @Param("employeeId") UUID employeeId,
                                                  @Param("siteId") UUID siteId,
                                                  @Param("from") OffsetDateTime from,
                                                  @Param("to") OffsetDateTime to);

    /** Today's check-ins for a specific employee across all sites, newest first. */
    @Query("SELECT c FROM CheckinRecord c WHERE c.tenantId = :tenantId AND c.employeeId = :employeeId " +
           "AND c.deletedAt IS NULL AND c.checkInAt >= :from AND c.checkInAt < :to ORDER BY c.checkInAt DESC")
    List<CheckinRecord> findTodayCheckins(@Param("tenantId") UUID tenantId,
                                           @Param("employeeId") UUID employeeId,
                                           @Param("from") OffsetDateTime from,
                                           @Param("to") OffsetDateTime to);

    /** Used by the employee dashboard's "needs my attention" count — mirrors
     *  CheckinService#getCheckinHistory(status="pending_review") scoping. */
    long countByTenantIdAndEmployeeIdAndStatusAndDeletedAtIsNull(UUID tenantId, UUID employeeId, String status);

    /** Count of check-in sessions still open (no checkout) that were opened on-or-after
     *  {@code since} — i.e. "on-site right now" as of today, not an unclosed session from days/
     *  weeks ago that the employee simply forgot to check out of (that scenario is tracked
     *  separately as a data-quality issue, see AttendanceSummary#missingCheckoutDays). Without
     *  this bound, a single forgotten checkout would silently and permanently inflate every
     *  "currently on-site" count shown to HR/supervisors (audit 2026-08-04). */
    @Query("SELECT COUNT(c) FROM CheckinRecord c WHERE c.tenantId = :tenantId AND c.checkOutAt IS NULL " +
           "AND c.sessionClosedAt IS NULL " +
           "AND c.checkInAt >= :since AND (:siteId IS NULL OR c.siteId = :siteId) AND c.deletedAt IS NULL")
    long countOpenSessions(@Param("tenantId") UUID tenantId, @Param("since") OffsetDateTime since, @Param("siteId") UUID siteId);

    /** #120 (2026-08-18): HR dashboard "pending review" count — checkins flagged pending_review
     *  (e.g. face verification mismatch) that HR hasn't overridden yet. */
    @Query("SELECT COUNT(c) FROM CheckinRecord c WHERE c.tenantId = :tenantId AND c.status = 'pending_review' " +
           "AND (:siteId IS NULL OR c.siteId = :siteId) AND c.deletedAt IS NULL")
    long countPendingReview(@Param("tenantId") UUID tenantId, @Param("siteId") UUID siteId);

    /** Open check-in sessions for a specific site, opened on-or-after {@code since} — same
     *  stale-session guard as {@link #countOpenSessions}, see its javadoc. */
    @Query("SELECT c FROM CheckinRecord c WHERE c.tenantId = :tenantId AND c.siteId = :siteId " +
           "AND c.checkOutAt IS NULL AND c.sessionClosedAt IS NULL " +
           "AND c.checkInAt >= :since AND c.deletedAt IS NULL ORDER BY c.checkInAt ASC")
    List<CheckinRecord> findOpenSessionsBySite(@Param("tenantId") UUID tenantId,
                                                @Param("siteId") UUID siteId,
                                                @Param("since") OffsetDateTime since);

    /** Open sessions that can still receive an immediate presence check. Unlike the dashboard
     * query above, this deliberately includes an overnight shift opened before midnight. */
    @Query("SELECT c FROM CheckinRecord c WHERE c.tenantId = :tenantId AND c.siteId = :siteId " +
           "AND c.checkOutAt IS NULL AND c.sessionClosedAt IS NULL " +
           "AND (c.sessionExpiresAt IS NULL OR c.sessionExpiresAt > :now) " +
           "AND c.deletedAt IS NULL ORDER BY c.checkInAt ASC")
    List<CheckinRecord> findUnexpiredOpenSessionsBySite(@Param("tenantId") UUID tenantId,
                                                        @Param("siteId") UUID siteId,
                                                        @Param("now") OffsetDateTime now);

    /** All non-deleted checkins whose check_in_at falls within the given UTC range, across EVERY
     *  tenant — intentionally unscoped, used only by the nightly AttendanceSummaryJob batch (a
     *  system job, not a user-triggered action, so tenant-agnostic is correct here). Do NOT reuse
     *  this for any user-facing/API-triggered recompute — see findCheckinsBetweenForTenant. */
    @Query("SELECT c FROM CheckinRecord c WHERE c.deletedAt IS NULL " +
           "AND c.checkInAt >= :from AND c.checkInAt < :to")
    List<CheckinRecord> findCheckinsBetween(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** Tenant-scoped (and optionally site-scoped) variant for the user-facing POST .../recompute
     *  endpoint — found via audit (2026-07-31) that the endpoint was calling the tenant-agnostic
     *  findCheckinsBetween above, letting any HR/Admin in ANY tenant trigger a recompute that
     *  touched every OTHER tenant's attendance data for that date. */
    @Query("SELECT c FROM CheckinRecord c WHERE c.tenantId = :tenantId AND c.deletedAt IS NULL " +
           "AND c.checkInAt >= :from AND c.checkInAt < :to " +
           "AND (:siteId IS NULL OR c.siteId = :siteId)")
    List<CheckinRecord> findCheckinsBetweenForTenant(@Param("tenantId") UUID tenantId,
                                                       @Param("siteId") UUID siteId,
                                                       @Param("from") OffsetDateTime from,
                                                       @Param("to") OffsetDateTime to);

    Optional<CheckinRecord> findByEmployeeIdAndClientNonceAndDeletedAtIsNull(UUID employeeId, UUID clientNonce);

    /** Finds any non-deleted record for the assignment where the given timestamp falls inside
     *  the actual checkout interval, or the logical interval of a missing-checkout session. */
    @Query("SELECT c FROM CheckinRecord c WHERE c.assignmentId = :assignmentId AND c.deletedAt IS NULL " +
           "AND c.checkInAt <= :checkinAt " +
           "AND (COALESCE(c.checkOutAt, c.sessionClosedAt) IS NULL " +
           "OR COALESCE(c.checkOutAt, c.sessionClosedAt) >= :checkinAt)")
    Optional<CheckinRecord> findOverlappingSession(@Param("assignmentId") UUID assignmentId,
                                                    @Param("checkinAt") OffsetDateTime checkinAt);

    @Query(value = """
            SELECT ST_Within(
                ST_SetSRID(ST_MakePoint(:lon, :lat), 4326),
                ST_Buffer(
                    ST_SetSRID(ST_GeomFromText(:polygonWkt), 4326)::geography,
                    :bufferMeters
                )::geometry
            )
            """, nativeQuery = true)
    boolean isPointWithinBufferedPolygon(
            @Param("lon") double lon,
            @Param("lat") double lat,
            @Param("polygonWkt") String polygonWkt,
            @Param("bufferMeters") int bufferMeters);

    // ── Column-scoped updates ────────────────────────────────────────────────
    // submitCheckout and the async face-result callback can run concurrently against the SAME
    // row (checkout fires the async job, then returns — the callback can arrive before checkout's
    // own transaction is even visible elsewhere). A "load full entity, mutate several fields,
    // save()" pattern in both places is a classic lost-update race: whichever transaction's
    // full-entity save() commits SECOND silently reverts the columns the OTHER one just wrote,
    // because Hibernate's save() writes every mapped column from its own (possibly stale)
    // in-memory copy. Each write path below touches ONLY the columns it actually owns, so two
    // concurrent updates to disjoint column sets can never clobber each other regardless of
    // commit order — this was caught via live testing (a checkout's check_out_at was observed
    // reverted to NULL by a fast-arriving face-result callback) and applies equally to the
    // check-in side, which had the identical latent risk.

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CheckinRecord c SET c.checkOutAt = :checkOutAt, "
            + "c.sessionClosedAt = :checkOutAt, c.sessionCloseReason = 'checkout', "
            + "c.checkOutLat = :checkOutLat, "
            + "c.checkOutLon = :checkOutLon, c.checkOutAccuracy = :checkOutAccuracy, "
            + "c.checkOutInsideGeofence = :checkOutInsideGeofence, c.workMinutes = :workMinutes "
            + "WHERE c.id = :id AND c.checkOutAt IS NULL AND c.sessionClosedAt IS NULL "
            + "AND (c.sessionExpiresAt IS NULL OR c.sessionExpiresAt > :checkOutAt)")
    int applyCheckout(@Param("id") UUID id,
                       @Param("checkOutAt") OffsetDateTime checkOutAt,
                       @Param("checkOutLat") Double checkOutLat,
                       @Param("checkOutLon") Double checkOutLon,
                       @Param("checkOutAccuracy") Double checkOutAccuracy,
                       @Param("checkOutInsideGeofence") boolean checkOutInsideGeofence,
                       @Param("workMinutes") int workMinutes);

    @Modifying
    @Query("UPDATE CheckinRecord c SET c.faceVerified = :faceVerified, c.livenessVerified = :livenessVerified, "
            + "c.faceVerifyScore = :score WHERE c.id = :id")
    int applyCheckinFaceResult(@Param("id") UUID id, @Param("faceVerified") Boolean faceVerified,
                                @Param("livenessVerified") Boolean livenessVerified, @Param("score") Double score);

    @Modifying
    @Query("UPDATE CheckinRecord c SET c.checkoutFaceVerified = :faceVerified, "
            + "c.checkoutLivenessVerified = :livenessVerified, c.checkoutFaceVerifyScore = :score WHERE c.id = :id")
    int applyCheckoutFaceResult(@Param("id") UUID id, @Param("faceVerified") Boolean faceVerified,
                                 @Param("livenessVerified") Boolean livenessVerified, @Param("score") Double score);

    /** Idempotent, race-safe escalation: only moves valid -> pending_review, never the reverse,
     *  and the WHERE guard means concurrent callers racing this never "un-escalate" each other. */
    @Modifying
    @Query("UPDATE CheckinRecord c SET c.status = 'pending_review' WHERE c.id = :id AND c.status = 'valid'")
    int escalateToPendingReviewIfValid(@Param("id") UUID id);
}
