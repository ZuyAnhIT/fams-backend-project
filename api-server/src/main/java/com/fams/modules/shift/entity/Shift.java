package com.fams.modules.shift.entity;

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
@Table(name = "shifts")
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "allow_overnight", nullable = false)
    private boolean allowOvernight;

    @Column(name = "allow_overtime", nullable = false)
    private boolean allowOvertime;

    @Column(name = "early_checkin_minutes", nullable = false)
    private int earlyCheckinMinutes;

    @Column(name = "late_checkout_minutes", nullable = false)
    private int lateCheckoutMinutes;

    /** #60 (docs/api/backend-feature-audit-2026-08-07.md): null = unlimited. Warn-only — never
     *  caps otMinutes itself, only sets AttendanceSummary.otDailyLimitExceeded for HR review. */
    @Column(name = "max_ot_minutes_per_day")
    private Integer maxOtMinutesPerDay;

    /** Null = unlimited. Compared against the employee's summed otMinutes across the ISO week
     *  (Mon-Sun) containing the attendance date — see AttendanceSummaryService#recompute. */
    @Column(name = "max_ot_minutes_per_week")
    private Integer maxOtMinutesPerWeek;

    /** gps_only | gps_face | gps_face_liveness | null (null = inherit the Site's policy). */
    @Column(name = "checkin_policy_override", length = 20)
    private String checkinPolicyOverride;

    @Column(nullable = false, length = 20)
    private String status;

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
        if (status == null) status = "active";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
