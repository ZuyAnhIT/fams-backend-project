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

    // Field named 'missingCheckout' so Lombok generates isMissingCheckout() getter
    @Column(name = "missing_checkout", nullable = false)
    private boolean missingCheckout;

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
