package com.fams.modules.violation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@Schema(description = "Result of updating the attendance impact flag on a violation")
public class AttendanceImpactResponse {

    @Schema(description = "Violation UUID")
    private UUID id;

    @Schema(description = "Whether this violation now affects the employee's attendance record — "
            + "authoritative for AttendanceSummary.hasRandomCheckFailure now that it has been reviewed "
            + "(2026-08-12 fix), overriding the default resolution-based auto-detection")
    private boolean affectsAttendance;
}
