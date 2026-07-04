package com.fams.modules.violation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request body for dismissing a violation")
public class DismissViolationRequest {

    @NotBlank
    @Schema(description = "Reason for dismissing the violation — required so HR actions are auditable",
            example = "Employee was working at an approved off-site location confirmed by manager.")
    private String reason;
}
