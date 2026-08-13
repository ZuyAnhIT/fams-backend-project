package com.fams.modules.tenant.entity;

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
@Table(name = "tenant_ip_whitelists")
public class TenantIpWhitelist {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "ip_address", nullable = false, length = 50)
    private String ipAddress;

    @Column(length = 100)
    private String label;

    /** Empty = applies to every role (equivalent to the old "all" scope). Non-empty = this
     *  entry only restricts users holding one of these role names — see V90 migration. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "tenant_ip_whitelist_roles", joinColumns = @JoinColumn(name = "ip_whitelist_id"))
    @Column(name = "role_name")
    @Builder.Default
    private Set<String> applicableRoleNames = new HashSet<>();

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

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
        isActive = true;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
