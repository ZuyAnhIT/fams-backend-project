package com.fams.modules.rbac.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "Assign one role to many users at once within a tenant — e.g. moving every employee "
        + "off a role that's being retired onto its replacement, without doing it one person at a time.")
public class BulkAssignRoleRequest {

    @Schema(description = "UUID of the tenant within which these role changes happen", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull(message = "tenantId is required")
    private UUID tenantId;

    @Schema(description = "UUID of the role to grant to every listed user", example = "550e8400-e29b-41d4-a716-446655440001")
    @NotNull(message = "roleId is required")
    private UUID roleId;

    @Schema(description = "Optional: also revoke this role from each listed user in the same tenant first — "
            + "the common case is moving everyone off an old role onto a new one in one action. Omit to only "
            + "grant, leaving any existing roles untouched.")
    private UUID revokeRoleId;

    @Schema(description = "Users to receive the role (1–500 per call)")
    @NotEmpty(message = "userIds must contain at least one user")
    @Size(max = 500, message = "At most 500 users per bulk request")
    private List<UUID> userIds;

    @Schema(description = "Optional: restrict every new assignment to these sites, same meaning as in "
            + "single-user role assignment. Applied identically to all users in this call.")
    private List<UUID> siteIds;
}
