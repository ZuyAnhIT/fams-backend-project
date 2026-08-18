package com.fams.modules.randomcheck.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class SubmitCheckResponseRequest {

    @NotNull(message = "latitude is required")
    @DecimalMin(value = "-90.0", message = "latitude must be >= -90")
    @DecimalMax(value = "90.0", message = "latitude must be <= 90")
    private BigDecimal latitude;

    @NotNull(message = "longitude is required")
    @DecimalMin(value = "-180.0", message = "longitude must be >= -180")
    @DecimalMax(value = "180.0", message = "longitude must be <= 180")
    private BigDecimal longitude;

    private BigDecimal accuracyMeters;

    /** Optional: URL or path of a previously uploaded face image */
    private String faceImageUrl;

    /** Base64-encoded selfie for async AI face verification (location_face / location_face_liveness modes) */
    private String employeePhotoBase64;

    /** Optional: liveness score [0,1] provided by the client SDK */
    @DecimalMin(value = "0.0", message = "livenessScore must be >= 0")
    @DecimalMax(value = "1.0", message = "livenessScore must be <= 1")
    private BigDecimal livenessScore;

    /** A PASSED, purpose=random_check active-liveness challenge (head-pose/blink sequence) from
     *  POST .../face-id/liveness-challenge, started at this check's siteId. Required (not
     *  optional) for location_face_liveness mode as of #104 (2026-08-18, upgraded from passive
     *  single-photo liveness by explicit user decision) — employeePhotoBase64 alone is no longer
     *  accepted for that mode. Ignored for location_only/location_face. */
    private UUID livenessChallengeId;
}
