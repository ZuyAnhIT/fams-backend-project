package com.fams.modules.randomcheck.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

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

    /** Optional: base64-encoded selfie for face-verification modes */
    private String faceImageUrl;

    /** Optional: liveness score [0,1] provided by the client SDK */
    @DecimalMin(value = "0.0", message = "livenessScore must be >= 0")
    @DecimalMax(value = "1.0", message = "livenessScore must be <= 1")
    private BigDecimal livenessScore;
}
