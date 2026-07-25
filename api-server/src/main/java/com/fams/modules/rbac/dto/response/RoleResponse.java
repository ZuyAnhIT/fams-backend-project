package com.fams.modules.rbac.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Summary of a role (list view)")
public class RoleResponse {

    @Schema(description = "Role UUID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "Role name", example = "Site Supervisor")
    private String name;

    @Schema(description = "Role description", example = "Manages a single construction site")
    private String description;

    @Schema(description = "True for built-in system roles that cannot be modified or deleted")
    private boolean isSystem;

    @Schema(description = "False if this custom role has been deactivated — existing holders keep it, but it can no longer be assigned to new users. Always true for system roles.")
    private boolean isActive;

    @Schema(description = "Tenant that owns this role (null for platform-wide system roles)")
    private UUID tenantId;

    @Schema(description = "Number of permissions assigned to this role", example = "5")
    private int permissionCount;

    @Schema(description = "Number of users currently holding this role (active assignments). Useful before attempting to delete — DELETE is blocked while this is > 0.", example = "3")
    private long assignmentCount;

    @Schema(description = "Creation timestamp (UTC)")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp (UTC)")
    private OffsetDateTime updatedAt;
}
