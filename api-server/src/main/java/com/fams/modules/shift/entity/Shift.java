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

    /** #81 gap fix (2026-08-17, decision from project owner): minutes of tolerance before a
     *  late first check-in is actually flagged late (AttendanceSummary.isLate/lateMinutes) —
     *  previously there was no grace period at all, so being 1 minute late already counted.
     *  Default 5 (migration V101). Arriving within grace still records lateMinutes=0; arriving
     *  beyond it records the FULL raw delay (not delay-minus-grace) — grace decides whether
     *  you're flagged late at all, it doesn't discount the minutes once you are. */
    @Column(name = "grace_minutes", nullable = false)
    private int graceMinutes;

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

    /** inherit | enabled | disabled. Controls automatic random checks for this shift. */
    @Column(name = "random_check_policy", nullable = false, length = 20)
    private String randomCheckPolicy;

    /** inherit | enabled | disabled. Controls immediate HR-triggered checks independently. */
    @Column(name = "manual_check_policy", nullable = false, length = 20)
    private String manualCheckPolicy;

    @Column(nullable = false, length = 20)
    private String status;

    /** At most one default shift per site (enforced by a partial unique index — see V99). Used
     *  to pre-select the shift when creating an assignment; purely advisory, does not affect
     *  check-in/check-out behavior. */
    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

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
        if (randomCheckPolicy == null) randomCheckPolicy = "inherit";
        if (manualCheckPolicy == null) manualCheckPolicy = "inherit";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
