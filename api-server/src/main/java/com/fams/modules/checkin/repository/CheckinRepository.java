package com.fams.modules.checkin.repository;

import com.fams.modules.checkin.entity.CheckinRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckinRepository extends JpaRepository<CheckinRecord, UUID>, JpaSpecificationExecutor<CheckinRecord> {

    /** Open session = no checkout yet for this assignment. */
    Optional<CheckinRecord> findByAssignmentIdAndCheckOutAtIsNullAndDeletedAtIsNull(UUID assignmentId);


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
}
