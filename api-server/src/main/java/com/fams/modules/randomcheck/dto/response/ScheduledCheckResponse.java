package com.fams.modules.randomcheck.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
public class ScheduledCheckResponse {

    @Schema(description = "Scheduled check UUID")
    private UUID id;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Assignment UUID")
    private UUID assignmentId;

    @Schema(description = "Employee UUID")
    private UUID employeeId;

    @Schema(description = "Employee full name — hydrated for list display, avoids N+1 directory lookups")
    private String employeeName;

    @Schema(description = "Site UUID")
    private UUID siteId;

    @Schema(description = "Site name — hydrated for list display, avoids N+1 directory lookups")
    private String siteName;

    @Schema(description = "Shift UUID")
    private UUID shiftId;

    @Schema(description = "Config UUID used to generate this check")
    private UUID configId;

    @Schema(description = "Snapshot of the config fields at generation time (JSON)")
    private String configSnapshot;

    @Schema(description = "Date this check was generated for", example = "2026-06-28")
    private LocalDate checkDate;

    @Schema(description = "1-based index of this check within the shift", example = "1")
    private int checkIndex;

    @Schema(description = "When the check notification should be dispatched")
    private OffsetDateTime scheduledAt;

    @Schema(description = "Deadline for the employee to respond")
    private OffsetDateTime expiresAt;

    @Schema(description = "Status: pending | sent | responded | no_response | cancelled")
    private String status;

    @Schema(description = "Creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Only set for manually (HR-)triggered checks — the required reason given for "
            + "targeting this specific employee, bypassing the config's applicableRoles filter")
    private String manualReason;

    @Schema(description = "Only set for manually (HR-)triggered checks — the user who triggered it")
    private java.util.UUID triggeredBy;

    @Schema(description = "Result of the employee's response — 'pass'/'fail', null if not yet responded")
    private String outcome;

    @Schema(description = "Comma-separated failure reasons (e.g. 'location_mismatch,face_fail'), null if passed or not yet responded")
    private String failureReason;
}
