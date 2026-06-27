package com.fams.modules.checkin.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "checkins")
public class CheckinRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    @Column(name = "shift_id")
    private UUID shiftId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "check_in_at", nullable = false)
    private OffsetDateTime checkInAt;

    @Column(name = "check_in_lat", nullable = false)
    private double checkInLat;

    @Column(name = "check_in_lon", nullable = false)
    private double checkInLon;

    @Column(name = "check_in_accuracy")
    private Double checkInAccuracy;

    @Column(name = "check_in_inside_geofence", nullable = false)
    private boolean checkInInsideGeofence;

    @Column(name = "check_out_at")
    private OffsetDateTime checkOutAt;

    @Column(name = "check_out_lat")
    private Double checkOutLat;

    @Column(name = "check_out_lon")
    private Double checkOutLon;

    @Column(name = "check_out_accuracy")
    private Double checkOutAccuracy;

    @Column(name = "check_out_inside_geofence")
    private Boolean checkOutInsideGeofence;

    @Column(name = "work_minutes")
    private Integer workMinutes;

    @Column(name = "gps_risk_score", nullable = false)
    private double gpsRiskScore;

    @Column(name = "device_id", length = 255)
    private String deviceId;

    @Column(columnDefinition = "TEXT")
    private String note;

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
        if (status == null) status = "valid";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
