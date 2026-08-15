package com.fams.modules.rbac.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

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
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column
    private String description;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem;

    /** True only for PLATFORM_ADMIN/PLATFORM_STAFF — distinguishes platform-tier system roles
     *  from tenant-tier ones (TENANT_ADMIN, HR_MANAGER, SITE_SUPERVISOR, EMPLOYEE), which
     *  otherwise share the exact same tenantId=null/isSystem=true signature. See V91
     *  migration for why this matters (visibility + assignment leak, both fixed 2026-08-14). */
    @Column(name = "is_platform_role", nullable = false)
    private boolean isPlatformRole;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @BatchSize(size = 30)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    @Builder.Default
    private Set<Permission> permissions = new HashSet<>();

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
