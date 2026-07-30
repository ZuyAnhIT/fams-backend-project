package com.fams.modules.checkin.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "GPS check-out submission payload")
public class SubmitCheckoutRequest {

    @Schema(description = "GPS latitude (WGS-84)", example = "21.0285")
    @NotNull(message = "latitude is required")
    @DecimalMin(value = "-90.0", message = "latitude must be between -90 and 90")
    @DecimalMax(value = "90.0",  message = "latitude must be between -90 and 90")
    private Double latitude;

    @Schema(description = "GPS longitude (WGS-84)", example = "105.8542")
    @NotNull(message = "longitude is required")
    @DecimalMin(value = "-180.0", message = "longitude must be between -180 and 180")
    @DecimalMax(value = "180.0",  message = "longitude must be between -180 and 180")
    private Double longitude;

    @Schema(description = "GPS horizontal accuracy in metres (optional)", example = "15.0")
    @DecimalMin(value = "0.0", message = "gpsAccuracy must be non-negative")
    private Double gpsAccuracy;

    @Schema(description = "Device identifier (optional)", example = "device-abc-123")
    private String deviceId;

    @Schema(description = "Base64-encoded selfie for face verification at checkout. Optional at "
            + "gps_only/gps_face sites (gps_face also accepts this OR livenessChallengeId); ignored "
            + "(livenessChallengeId is required instead) at gps_face_liveness sites.")
    private String employeePhotoBase64;

    @Schema(description = "Require liveness check in addition to face match — only meaningful "
            + "together with employeePhotoBase64", example = "false", defaultValue = "false")
    private boolean requiresLiveness;

    @Schema(description = "A PASSED, purpose=checkout active-liveness challenge UUID (from POST "
            + ".../face-id/liveness-challenge + .../frames) — required instead of employeePhotoBase64 "
            + "at a gps_face_liveness site.", nullable = true)
    private java.util.UUID livenessChallengeId;
}
