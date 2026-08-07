package com.fams.modules.golive.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A formal go-live sign-off record for one tenant — added 2026-08-06 per FE feedback: the UAT
 * checklist (docs/testing/manual-test-scenarios.md §B.8) and go-live checklist
 * (docs/deployment/go-live-checklist.md) previously only existed as markdown, with no way to
 * persist "who tested this tenant, on what build, which steps passed, what evidence, who signed
 * off" as an actual compliance artifact. One row = one go-live attempt for one tenant (a tenant
 * can have multiple rows over time — e.g. a failed run followed by a passing re-run).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "go_live_records")
public class GoLiveRecord {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "environment", nullable = false, length = 50)
    private String environment;

    @Column(name = "build_version", nullable = false, length = 100)
    private String buildVersion;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** Each element: {"stepName": "...", "result": "PASS|FAIL|SKIP", "note": "...",
     *  "evidenceUrl": "..."} — free-form list, not a fixed enum of steps, so the checklist in
     *  docs/deployment/go-live-checklist.md can evolve without a migration. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "steps", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> steps;

    @Column(name = "performed_by", nullable = false)
    private UUID performedBy;

    @Column(name = "performed_by_name", length = 255)
    private String performedByName;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_by_name", length = 255)
    private String approvedByName;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "approval_note", columnDefinition = "TEXT")
    private String approvalNote;

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
        if (status == null) status = STATUS_DRAFT;
        if (steps == null) steps = List.of();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
