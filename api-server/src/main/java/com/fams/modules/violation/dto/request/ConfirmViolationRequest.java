package com.fams.modules.violation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for confirming a violation as valid")
public class ConfirmViolationRequest {

    @Schema(description = "Optional internal note from HR about this confirmation",
            example = "Confirmed after reviewing GPS logs — employee was clearly outside the geofence.")
    private String note;
}
