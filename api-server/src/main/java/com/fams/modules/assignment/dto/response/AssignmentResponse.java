package com.fams.modules.assignment.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Employee assignment to a site")
public class AssignmentResponse {

    @Schema(description = "Assignment UUID")
    private UUID id;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Site UUID")
    private UUID siteId;

    @Schema(description = "Employee UUID")
    private UUID employeeId;

    @Schema(description = "Shift template UUID, or null if not linked to a specific shift")
    private UUID shiftId;

    @Schema(description = "Assignment start date", example = "2026-07-01")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @Schema(description = "Assignment end date (inclusive), or null for open-ended", example = "2026-12-31")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    @Schema(description = "Employee's role at the site: worker or supervisor", example = "worker")
    private String role;

    @Schema(description = "Status: active or cancelled", example = "active")
    private String status;

    @Schema(description = "Optional notes")
    private String notes;

    @Schema(description = "UUID of the user who created this assignment")
    private UUID createdBy;

    @Schema(description = "Creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private OffsetDateTime updatedAt;
}
