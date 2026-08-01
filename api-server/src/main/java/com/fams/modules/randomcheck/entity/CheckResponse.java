package com.fams.modules.randomcheck.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "check_responses")
public class CheckResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "scheduled_check_id", nullable = false)
    private UUID scheduledCheckId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "responded_at", nullable = false)
    private OffsetDateTime respondedAt;

    @Column(name = "latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "accuracy_meters", precision = 8, scale = 2)
    private BigDecimal accuracyMeters;

    @Column(name = "face_image_url")
    private String faceImageUrl;

    @Column(name = "liveness_score", precision = 5, scale = 4)
    private BigDecimal livenessScore;

    @Column(name = "location_verified", nullable = false)
    private boolean locationVerified;

    @Column(name = "face_verified")
    private Boolean faceVerified;

    @Column(name = "liveness_verified")
    private Boolean livenessVerified;

    @Column(name = "face_verify_score")
    private Double faceVerifyScore;

    @Column(name = "outcome", nullable = false, length = 10)
    private String outcome;

    @Column(name = "failure_reason")
    private String failureReason;

    // True iff a photo was actually forwarded to the async AI verify job at submit() time —
    // the reliable signal that fams-ai has a selfie on disk at checkins/{tenantId}/{this.id}.jpg,
    // retrievable via GET .../scheduled-checks/{checkId}/photo. See V82 migration.
    @Column(name = "photo_submitted", nullable = false)
    private boolean photoSubmitted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
