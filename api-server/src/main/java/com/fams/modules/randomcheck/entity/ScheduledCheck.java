package com.fams.modules.randomcheck.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scheduled_checks")
public class ScheduledCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "shift_id")
    private UUID shiftId;

    @Column(name = "config_id")
    private UUID configId;

    @Column(name = "config_snapshot", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String configSnapshot;

    @Column(name = "check_date", nullable = false)
    private LocalDate checkDate;

    @Column(name = "check_index", nullable = false)
    private int checkIndex;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    // Only set for manually (HR-)triggered checks — manual checks intentionally bypass the
    // applicableRoles population filter (targeting one specific employee), so a required reason
    // + who-triggered gives an audit trail for that override. Null for auto-generated checks.
    @Column(name = "manual_reason", columnDefinition = "TEXT")
    private String manualReason;

    @Column(name = "triggered_by")
    private UUID triggeredBy;

    /** #99 (2026-08-18): who/when/why a check was cancelled — null for checks never cancelled.
     *  cancelledBy is null for system-triggered cancellation (e.g. assignment cancelled), set
     *  for HR-initiated cancellation via the cancel endpoint. */
    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancelled_reason", columnDefinition = "TEXT")
    private String cancelledReason;

    /** #100 (2026-08-18): when the check was actually dispatched (status flipped to 'sent') and
     *  which Notification row carries the push/in-app message for it — previously only inferable
     *  indirectly via notification_delivery_logs.created_at / metadata.checkId. */
    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "notification_id")
    private UUID notificationId;

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
        if (status == null) status = "pending";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
