package com.fams.modules.randomcheck.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
@Schema(description = "A pending or sent random check visible to the assigned employee, with countdown")
public class EmployeePendingCheckResponse {

    @Schema(description = "Scheduled check UUID")
    private UUID id;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Assignment UUID")
    private UUID assignmentId;

    @Schema(description = "Employee UUID")
    private UUID employeeId;

    @Schema(description = "Site UUID")
    private UUID siteId;

    @Schema(description = "Shift UUID")
    private UUID shiftId;

    @Schema(description = "Config UUID used to generate this check")
    private UUID configId;

    @Schema(description = "Snapshot of the config fields at generation time (JSON)")
    private String configSnapshot;

    @Schema(description = "Date this check was generated for", example = "2026-07-11")
    private LocalDate checkDate;

    @Schema(description = "1-based index of this check within the shift", example = "1")
    private int checkIndex;

    @Schema(description = "When the check notification was/will be dispatched")
    private OffsetDateTime scheduledAt;

    @Schema(description = "Deadline for the employee to respond")
    private OffsetDateTime expiresAt;

    @Schema(description = "Status: pending | sent")
    private String status;

    @Schema(description = "Seconds remaining until the deadline (expiresAt - now). " +
                          "Negative if already expired. Null for pending checks not yet dispatched.")
    private Long secondsRemaining;

    @Schema(description = "Creation timestamp")
    private OffsetDateTime createdAt;
}
