package com.fams.modules.checkin.repository;

import com.fams.modules.checkin.entity.CheckinRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
