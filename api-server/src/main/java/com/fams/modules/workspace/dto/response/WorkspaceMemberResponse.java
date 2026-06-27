package com.fams.modules.workspace.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class WorkspaceMemberResponse {

    @Schema(description = "Membership record UUID")
    private UUID id;

    @Schema(description = "Workspace UUID")
    private UUID workspaceId;

    @Schema(description = "Employee UUID")
    private UUID employeeId;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Workspace-level role", example = "member")
    private String role;

    @Schema(description = "UUID of the user who made the assignment")
    private UUID assignedBy;

    @Schema(description = "Assignment timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private OffsetDateTime updatedAt;
}
