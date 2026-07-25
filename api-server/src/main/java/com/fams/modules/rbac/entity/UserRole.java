package com.fams.modules.rbac.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_roles")
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /** Empty = unrestricted across the whole tenant (default, unchanged behavior). One or
     *  more entries = this specific assignment only grants access within those sites — e.g.
     *  a SITE_SUPERVISOR scoped to just their own site instead of every site in the company.
     *  See SiteScopeService for how this is resolved into an effective allowed-site set. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_role_sites", joinColumns = @JoinColumn(name = "user_role_id"))
    @Column(name = "site_id")
    @Builder.Default
    private Set<UUID> siteIds = new HashSet<>();

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
