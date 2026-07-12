package com.fams.modules.employee.entity;

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
@Table(name = "face_profiles")
public class FaceProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "consent_given", nullable = false)
    private boolean consentGiven;

    @Column(name = "consent_given_at")
    private OffsetDateTime consentGivenAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    // embedding column intentionally not mapped — fams-ai writes it directly via psycopg2

    @Column(name = "enrolled_at")
    private OffsetDateTime enrolledAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "embedding_deleted", nullable = false)
    private boolean embeddingDeleted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "not_enrolled";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
