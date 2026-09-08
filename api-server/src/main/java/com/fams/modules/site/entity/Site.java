package com.fams.modules.site.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fams.shared.time.VietnamTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sites")
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String address;

    private Double latitude;

    private Double longitude;

    @Column(nullable = false, length = 50)
    private String timezone;

    @Column(nullable = false, length = 20)
    private String status;

    /** gps_only | gps_face | gps_face_liveness — see CheckinService.resolveEffectiveCheckinPolicy
     *  for how this combines with a Shift's optional override. */
    @Column(name = "checkin_policy", nullable = false, length = 20)
    private String checkinPolicy;

    /** #130 (2026-08-18): when true, the employee-facing check-in map omits the geofence polygon
     *  shape (current location + site center marker still shown) — an HR opt-in per site, e.g.
     *  for a security-sensitive boundary. Defaults to false (unchanged prior behavior: always
     *  shown) so this never silently hides anything for existing sites. */
    @Column(name = "hide_polygon_from_employee", nullable = false)
    private boolean hidePolygonFromEmployee;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (timezone == null) timezone = VietnamTime.ID;
        if (status == null) status = "active";
        if (checkinPolicy == null) checkinPolicy = "gps_only";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
