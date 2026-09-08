package com.fams.modules.randomcheck.entity;

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
@Table(name = "random_check_configs")
public class RandomCheckConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "site_id")
    private UUID siteId;

    @Column(name = "checks_per_shift", nullable = false)
    private int checksPerShift;

    @Column(name = "min_interval_minutes", nullable = false)
    private int minIntervalMinutes;

    @Column(name = "allowed_start_time", nullable = false)
    private LocalTime allowedStartTime;

    @Column(name = "allowed_end_time", nullable = false)
    private LocalTime allowedEndTime;

    @Column(name = "window_mode", nullable = false, length = 20)
    private String windowMode;

    @Column(name = "check_mode", nullable = false, length = 30)
    private String checkMode;

    @Column(name = "applicable_roles", nullable = false)
    private String applicableRoles;

    @Column(name = "response_window_seconds", nullable = false)
    private int responseWindowSeconds;

    // Informational escalation point only — crossing this many failed/no_response random checks
    // in a calendar month for one employee+site surfaces "exceeds threshold" on the HR monthly
    // report (ReportService). Never auto-triggers a payroll change; HR decides via /adjust.
    @Column(name = "failure_escalation_threshold", nullable = false)
    private int failureEscalationThreshold;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "manual_checks_allowed", nullable = false)
    private boolean manualChecksAllowed;

    @Column(name = "created_by", nullable = false)
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
        if (checkMode == null) checkMode = "location_only";
        if (windowMode == null) windowMode = "full_shift";
        if (applicableRoles == null) applicableRoles = "";
        if (failureEscalationThreshold <= 0) failureEscalationThreshold = 3;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
