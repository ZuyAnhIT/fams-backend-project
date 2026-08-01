package com.fams.modules.randomcheck.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
@Schema(description = "Employee-owned, safe view of a scheduled check's result — used to poll for the "
        + "outcome after submitting a response with a photo (async AI verification). Never includes raw "
        + "embeddings, image storage paths, or any other employee's data.")
public class MyCheckResultResponse {

    @Schema(description = "Scheduled check UUID")
    private UUID checkId;

    @Schema(description = "Scheduled check status: pending | sent | responded | no_response | cancelled")
    private String status;

    @Schema(description = "'pending' while async face/liveness verification is still in flight (photo "
            + "submitted, faceVerified still null), 'completed' once a final outcome exists or the check "
            + "reached a terminal state (no_response/cancelled) with nothing left to wait for.",
            example = "completed", allowableValues = {"pending", "completed"})
    private String processingStatus;

    @Schema(description = "'pass'/'fail', null until responded and (if applicable) async verification finishes")
    private String outcome;

    @Schema(description = "Comma-separated failure reasons, null if passed or not yet determined")
    private String failureReason;

    @Schema(description = "Location check result, null if not yet responded")
    private Boolean locationVerified;

    @Schema(description = "Face match result — null while pending async verification or if mode doesn't require it")
    private Boolean faceVerified;

    @Schema(description = "Liveness result — null while pending async verification or if mode doesn't require it")
    private Boolean livenessVerified;

    @Schema(description = "AI face match confidence score, null until async verification finishes")
    private Double faceVerifyScore;

    @Schema(description = "When the employee submitted their response, null if not yet responded")
    private OffsetDateTime respondedAt;
}
