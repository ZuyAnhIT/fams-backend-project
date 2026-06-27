package com.fams.modules.geofence.repository;

import com.fams.modules.geofence.entity.Geofence;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GeofenceRepository extends JpaRepository<Geofence, UUID> {

    Optional<Geofence> findBySiteIdAndStatusAndDeletedAtIsNull(UUID siteId, String status);

    Page<Geofence> findBySiteIdAndTenantIdAndDeletedAtIsNull(UUID siteId, UUID tenantId, Pageable pageable);

    @Modifying
    @Query("UPDATE Geofence g SET g.status = 'superseded', g.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE g.siteId = :siteId AND g.status = 'active' AND g.deletedAt IS NULL")
    void supersedeBySiteId(@Param("siteId") UUID siteId);
}
