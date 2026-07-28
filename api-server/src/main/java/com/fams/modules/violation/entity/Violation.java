package com.fams.modules.violation.entity;

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
@Table(name = "violations")
public class Violation {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "scheduled_check_id")
    private UUID scheduledCheckId;

    @Column(name = "check_response_id")
    private UUID checkResponseId;

    /** Set when this violation originates from a regular (non-random-check) check-in whose
     *  site requires Face ID and the async face verification came back failed/errored. */
    @Column(name = "checkin_id")
    private UUID checkinId;

    @Column(name = "violation_type", nullable = false, length = 30)
    private String violationType;

    @Column(name = "check_date", nullable = false)
    private LocalDate checkDate;

    @Column(name = "description")
    private String description;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "affects_attendance", nullable = false)
    private boolean affectsAttendance;

    @Column(name = "resolution", length = 20)
    private String resolution;

    @Column(name = "resolution_reason", columnDefinition = "TEXT")
    private String resolutionReason;

    @Column(name = "employee_note", columnDefinition = "TEXT")
    private String employeeNote;

    @Column(name = "employee_photo_url")
    private String employeePhotoUrl;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
