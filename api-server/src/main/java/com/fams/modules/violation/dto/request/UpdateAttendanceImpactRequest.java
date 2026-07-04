package com.fams.modules.violation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Request body for updating whether a violation affects the employee's attendance record")
public class UpdateAttendanceImpactRequest {

    @NotNull
    @Schema(description = "Set to true to flag the violation as affecting attendance; false to clear the flag",
            example = "true")
    private Boolean affectsAttendance;
}
