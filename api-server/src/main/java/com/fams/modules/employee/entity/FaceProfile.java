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

    /** Which version of the consent text the employee agreed to — lets a later policy update
     *  invalidate old consents (see FaceIdService.CURRENT_CONSENT_VERSION / isConsentCurrent). */
    @Column(name = "consent_version", length = 20)
    private String consentVersion;

    @Column(name = "consent_ip", length = 64)
    private String consentIp;

    @Column(name = "consent_device")
    private String consentDevice;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    // embedding / pending_embedding columns intentionally not mapped — fams-ai is the only
    // component that ever reads or writes raw biometric vectors, via psycopg2.

    /** Anti-spoof confidence (0-1, worst photo in the enrollment batch) captured by fams-ai at
     *  enrollment time and promoted from pending_quality_score on approve (#127, 2026-08-18) —
     *  unlike embedding this is a scalar quality signal, not a raw biometric vector, so it's
     *  safe to map here for the Face ID report. */
    @Column(name = "quality_score")
    private Double qualityScore;

    @Column(name = "pending_quality_score")
    private Double pendingQualityScore;

    @Column(name = "enrolled_at")
    private OffsetDateTime enrolledAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /** Why this profile was revoked — captured by Java (not fams-ai) since it's a pure business
     *  field, not a biometric one. Null for auto-revoke-on-termination's system reason text is
     *  still populated (see FaceIdService.autoRevokeOnTermination) so "why" is never a mystery. */
    @Column(name = "deleted_reason", length = 500)
    private String deletedReason;

    /** Who triggered the revoke — null when system-triggered (e.g. auto-revoke on termination),
     *  distinct from a blank/forgotten field. */
    @Column(name = "deleted_by")
    private UUID deletedBy;

    @Column(name = "embedding_deleted", nullable = false)
    private boolean embeddingDeleted;

    /** none | pending | rejected — independent of `status`, which stays the last APPROVED
     *  state so an in-review re-enrollment never interrupts the employee's ability to check in
     *  with their currently-approved face. See FaceIdService for the full state machine. */
    @Column(name = "review_status", nullable = false, length = 20)
    private String reviewStatus;

    @Column(name = "pending_photo_count")
    private Integer pendingPhotoCount;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

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
        if (reviewStatus == null) reviewStatus = "none";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
