package com.fams.modules.workspace.entity;

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
@Table(name = "workspace_members")
public class WorkspaceMember {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 50)
    private String role;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    /** True if this is the employee's primary workspace — at most one active (deletedAt IS NULL)
     *  primary membership per (tenant, employee), enforced both here and by a DB partial unique
     *  index (see V96 migration). */
    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    /** When this membership actually starts (HR may back/future-date it) — distinct from
     *  createdAt, which is just when the row was inserted. */
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /** When this membership ended (transfer or explicit removal) — distinct from the generic
     *  deletedAt soft-delete column so "left" is recorded explicitly rather than inferred. */
    @Column(name = "left_at")
    private OffsetDateTime leftAt;

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
        if (role == null) role = "member";
        if (effectiveFrom == null) effectiveFrom = now.toLocalDate();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
