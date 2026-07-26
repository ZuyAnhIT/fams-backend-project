package com.fams.modules.workspace.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class WorkspaceResponse {

    @Schema(description = "Workspace UUID")
    private UUID id;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Workspace name", example = "Engineering")
    private String name;

    @Schema(description = "Optional description")
    private String description;

    @Schema(description = "Workspace type: department or team", example = "department")
    private String type;

    @Schema(description = "Parent workspace UUID (null if top-level)")
    private UUID parentId;

    @Schema(description = "Status: active or inactive", example = "active")
    private String status;

    @Schema(description = "Number of active employees currently in this workspace. Useful before deactivating/deleting.", example = "12")
    private long activeMemberCount;

    @Schema(description = "Number of active child workspaces under this one (direct children only). Useful before deleting.", example = "2")
    private long childWorkspaceCount;

    @Schema(description = "UUID of the user who created this workspace")
    private UUID createdBy;

    @Schema(description = "Creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private OffsetDateTime updatedAt;
}
