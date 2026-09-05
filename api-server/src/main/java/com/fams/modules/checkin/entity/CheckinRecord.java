package com.fams.modules.checkin.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;
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

    // Snapshot of the shift's time-affecting fields AS OF check-in time — never re-derived from
    // a live Shift lookup afterward, so editing the Shift later (payroll/audit correctness) can
    // never retroactively change how this record's work-minutes/late/OT were computed.
    @Column(name = "shift_start_time")
    private LocalTime shiftStartTime;

    @Column(name = "shift_end_time")
    private LocalTime shiftEndTime;

    @Column(name = "shift_allow_overnight")
    private Boolean shiftAllowOvernight;

    @Column(name = "shift_allow_overtime")
    private Boolean shiftAllowOvertime;

    @Column(name = "shift_late_checkout_minutes")
    private Integer shiftLateCheckoutMinutes;

    /** #81 (2026-08-17): same snapshot-at-check-in invariant as the other shift_* fields —
     *  minutes of tolerance before a late first check-in is flagged late. */
    @Column(name = "shift_grace_minutes")
    private Integer shiftGraceMinutes;

    /** #60 (docs/api/backend-feature-audit-2026-08-07.md): same snapshot-at-check-in
     *  invariant as the other shift_* fields above. */
    @Column(name = "shift_max_ot_minutes_per_day")
    private Integer shiftMaxOtMinutesPerDay;

    @Column(name = "shift_max_ot_minutes_per_week")
    private Integer shiftMaxOtMinutesPerWeek;

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

    /**
     * Logical session closure. For a normal checkout this equals checkOutAt. For a forgotten
     * checkout it stores the scheduled cutoff while checkOutAt deliberately remains null, so
     * attendance/audit screens can still distinguish missing evidence from a real checkout.
     */
    @Column(name = "session_closed_at")
    private OffsetDateTime sessionClosedAt;

    @Column(name = "session_close_reason", length = 30)
    private String sessionCloseReason;

    /** Immutable checkout deadline calculated from the shift snapshot at check-in. This makes
     *  proactive expiry queryable and keeps later Shift edits from changing an open session. */
    @Column(name = "session_expires_at")
    private OffsetDateTime sessionExpiresAt;

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

    @Column(name = "employee_note", columnDefinition = "TEXT")
    private String employeeNote;

    @Column(name = "employee_photo_url")
    private String employeePhotoUrl;

    @Column(name = "face_verified")
    private Boolean faceVerified;

    @Column(name = "liveness_verified")
    private Boolean livenessVerified;

    @Column(name = "face_verify_score")
    private Double faceVerifyScore;

    @Column(name = "checkout_face_verified")
    private Boolean checkoutFaceVerified;

    @Column(name = "checkout_liveness_verified")
    private Boolean checkoutLivenessVerified;

    @Column(name = "checkout_face_verify_score")
    private Double checkoutFaceVerifyScore;

    @Column(name = "client_nonce")
    private UUID clientNonce;

    // Snapshot of the resolved (site, with shift override) policy AT CHECK-IN TIME — checkout
    // enforces against THIS, not a live re-resolve, so a policy change mid-shift can't strand an
    // already-checked-in employee at checkout. Null for records predating this column (V78);
    // callers fall back to a live resolve for those. See CheckinService.enforceCheckinPolicy.
    @Column(name = "effective_checkin_policy", length = 20)
    private String effectiveCheckinPolicy;

    // "online" (submitCheckin) | "offline" (OfflineSyncService) — lets HR distinguish
    // live-submitted records from ones synced later from a device that was offline.
    @Column(nullable = false, length = 10)
    private String source;

    @Column(name = "overridden_by")
    private UUID overriddenBy;

    @Column(name = "overridden_at")
    private OffsetDateTime overriddenAt;

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
        if (source == null) source = "online";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
