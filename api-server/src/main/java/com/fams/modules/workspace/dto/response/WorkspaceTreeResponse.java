package com.fams.modules.workspace.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class WorkspaceTreeResponse {

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

    @Schema(description = "Number of active employees currently in this workspace.", example = "12")
    private long activeMemberCount;

    @Schema(description = "Number of active direct child workspaces (equals children.size(), provided for convenience so callers don't have to count the list themselves).", example = "2")
    private long childWorkspaceCount;

    @Schema(description = "UUID of the user who created this workspace")
    private UUID createdBy;

    @Schema(description = "Creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private OffsetDateTime updatedAt;

    @Schema(description = "Child workspaces")
    private List<WorkspaceTreeResponse> children;
}
