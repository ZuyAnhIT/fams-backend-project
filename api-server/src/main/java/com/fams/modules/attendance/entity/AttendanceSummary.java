package com.fams.modules.attendance.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "attendance_summaries")
public class AttendanceSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "shift_id")
    private UUID shiftId;

    @Column(name = "assignment_id")
    private UUID assignmentId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "first_checkin_at")
    private OffsetDateTime firstCheckinAt;

    @Column(name = "last_checkout_at")
    private OffsetDateTime lastCheckoutAt;

    @Column(name = "total_work_minutes", nullable = false)
    private int totalWorkMinutes;

    @Column(name = "session_count", nullable = false)
    private int sessionCount;

    @Column(nullable = false, length = 20)
    private String status;

    // Field named 'late' so Lombok generates isLate() getter and setLate()/builder .late()
    @Column(name = "is_late", nullable = false)
    private boolean late;

    @Column(name = "late_minutes", nullable = false)
    private int lateMinutes;

    // Field named 'earlyLeave' so Lombok generates isEarlyLeave() getter
    @Column(name = "is_early_leave", nullable = false)
    private boolean earlyLeave;

    @Column(name = "early_leave_minutes", nullable = false)
    private int earlyLeaveMinutes;

    @Column(name = "ot_minutes", nullable = false)
    private int otMinutes;

    // #60 (docs/api/backend-feature-audit-2026-08-07.md): warn-only, same non-mutating stance
    // as hasRandomCheckFailure below — otMinutes itself is never capped, HR reviews and decides
    // via /adjust. See V88 migration + Shift.maxOtMinutesPerDay/maxOtMinutesPerWeek.
    @Column(name = "ot_daily_limit_exceeded", nullable = false)
    private boolean otDailyLimitExceeded;

    @Column(name = "ot_weekly_limit_exceeded", nullable = false)
    private boolean otWeeklyLimitExceeded;

    // Field named 'missingCheckout' so Lombok generates isMissingCheckout() getter
    @Column(name = "missing_checkout", nullable = false)
    private boolean missingCheckout;

    // All computed fields above (workMinutes/late/earlyLeave/ot/missingCheckout) only aggregate
    // status='valid' sessions — these 2 flags surface WHY a day's numbers might look
    // provisional/incomplete (a pending_review or rejected session was excluded), instead of
    // silently dropping them with no explanation. See V79 migration.
    @Column(name = "has_pending_review_session", nullable = false)
    private boolean hasPendingReviewSession;

    @Column(name = "has_rejected_session", nullable = false)
    private boolean hasRejectedSession;

    // True if >=1 random check (scheduled_checks) for this employee/site/date ended in
    // status='no_response' or a check_response with outcome='fail'. Informational flag only —
    // never mutates totalWorkMinutes/OT/etc — HR decides via /adjust. See V80 migration and
    // docs/api/random-check-config-review.md §1.
    @Column(name = "has_random_check_failure", nullable = false)
    private boolean hasRandomCheckFailure;

    @Column(name = "adjustment_reason", columnDefinition = "TEXT")
    private String adjustmentReason;

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
        if (status == null) status = "present";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
