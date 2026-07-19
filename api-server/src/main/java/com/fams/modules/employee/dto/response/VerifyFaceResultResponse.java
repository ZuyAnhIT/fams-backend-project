package com.fams.modules.employee.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Polled result of a standalone face verify request")
public class VerifyFaceResultResponse {

    @Schema(description = "pending | pass | fail", example = "pass")
    private String status;

    @Schema(description = "Whether the face matched the enrolled embedding; null while pending", nullable = true)
    private Boolean faceVerified;

    @Schema(description = "Whether liveness check passed; null while pending or when not requested", nullable = true)
    private Boolean livenessVerified;

    @Schema(description = "Face match confidence score (0–1); null while pending", nullable = true)
    private Double score;

    @Schema(description = "Error code on failure: FACE_MISMATCH | LIVENESS_FAILED | TIMEOUT; null on pass or pending",
            nullable = true)
    private String errorCode;
}
