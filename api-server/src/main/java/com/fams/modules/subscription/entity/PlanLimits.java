package com.fams.modules.subscription.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "plan_limits")
public class PlanLimits {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "plan_id", nullable = false, unique = true)
    private UUID planId;

    /** null means unlimited */
    @Column(name = "max_employees")
    private Integer maxEmployees;

    /** null means unlimited */
    @Column(name = "max_sites")
    private Integer maxSites;

    /** null means unlimited (GB) */
    @Column(name = "max_storage_gb")
    private Integer maxStorageGb;

    /** null means unlimited */
    @Column(name = "max_random_checks_per_month")
    private Integer maxRandomChecksPerMonth;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

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
