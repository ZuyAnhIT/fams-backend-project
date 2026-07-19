package com.fams.modules.employee.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@Schema(description = "Response returned immediately after submitting a face verify request")
public class VerifyFaceSubmitResponse {

    @Schema(description = "Unique ID used to poll for the result")
    private UUID verifyRequestId;

    @Schema(description = "Initial status — always 'pending'", example = "pending")
    private String status;
}
