package com.fams.modules.employee.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Result of submitting frames for a liveness challenge")
public class LivenessChallengeResultResponse {

    @Schema(description = "passed | failed", example = "passed")
    private String status;

    @Schema(description = "Human-readable reason when status=failed, null otherwise", nullable = true)
    private String reason;

    @Schema(description = "Per-frame detail: which action was expected vs detected — raw passthrough "
            + "from fams-ai, useful for showing the employee exactly which step failed")
    private Object steps;
}
