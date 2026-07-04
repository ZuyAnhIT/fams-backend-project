package com.fams.modules.violation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Result of a violation resolution action (confirm or dismiss)")
public class ViolationActionResponse {

    @Schema(description = "Violation UUID")
    private UUID id;

    @Schema(description = "Resolution outcome", allowableValues = {"confirmed", "dismissed"})
    private String resolution;

    @Schema(description = "Optional reason or note supplied by HR")
    private String resolutionReason;

    @Schema(description = "Whether the violation is now resolved")
    private boolean resolved;

    @Schema(description = "When the violation was resolved")
    private OffsetDateTime resolvedAt;

    @Schema(description = "UUID of the HR user who resolved the violation")
    private UUID resolvedBy;
}
