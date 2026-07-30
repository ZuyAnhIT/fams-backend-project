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

public interface CheckinRepository extends JpaRepository<CheckinRecord, UUID>, JpaSpecificationExecutor<CheckinRecord> {

    /** Open session = no checkout yet for this assignment. */
    Optional<CheckinRecord> findByAssignmentIdAndCheckOutAtIsNullAndDeletedAtIsNull(UUID assignmentId);

    /** An employee physically cannot be checked in at two places at once, even across different
     *  assignments/sites — used to block a new check-in while any prior session (any site) is
     *  still open, instead of only checking the one assignment being checked into. */
    Optional<CheckinRecord> findByTenantIdAndEmployeeIdAndCheckOutAtIsNullAndDeletedAtIsNull(
            UUID tenantId, UUID employeeId);


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

    /** Count of currently open check-in sessions (no checkout) for the tenant. */
    @Query("SELECT COUNT(c) FROM CheckinRecord c WHERE c.tenantId = :tenantId AND c.checkOutAt IS NULL AND c.deletedAt IS NULL")
    long countOpenSessions(@Param("tenantId") UUID tenantId);

    /** All open check-in sessions for a specific site (employees currently on-site). */
    @Query("SELECT c FROM CheckinRecord c WHERE c.tenantId = :tenantId AND c.siteId = :siteId " +
           "AND c.checkOutAt IS NULL AND c.deletedAt IS NULL ORDER BY c.checkInAt ASC")
    List<CheckinRecord> findOpenSessionsBySite(@Param("tenantId") UUID tenantId,
                                                @Param("siteId") UUID siteId);

    /** All non-deleted checkins whose check_in_at falls within the given UTC range. Used by the daily summary job. */
    @Query("SELECT c FROM CheckinRecord c WHERE c.deletedAt IS NULL " +
           "AND c.checkInAt >= :from AND c.checkInAt < :to")
    List<CheckinRecord> findCheckinsBetween(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    Optional<CheckinRecord> findByEmployeeIdAndClientNonceAndDeletedAtIsNull(UUID employeeId, UUID clientNonce);

    /** Finds any non-deleted record for the assignment where the given timestamp falls inside the session. */
    @Query("SELECT c FROM CheckinRecord c WHERE c.assignmentId = :assignmentId AND c.deletedAt IS NULL " +
           "AND c.checkInAt <= :checkinAt AND (c.checkOutAt IS NULL OR c.checkOutAt >= :checkinAt)")
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

    @Modifying
    @Query("UPDATE CheckinRecord c SET c.checkOutAt = :checkOutAt, c.checkOutLat = :checkOutLat, "
            + "c.checkOutLon = :checkOutLon, c.checkOutAccuracy = :checkOutAccuracy, "
            + "c.checkOutInsideGeofence = :checkOutInsideGeofence, c.workMinutes = :workMinutes "
            + "WHERE c.id = :id")
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
