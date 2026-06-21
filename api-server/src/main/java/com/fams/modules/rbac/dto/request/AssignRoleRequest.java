package com.fams.modules.rbac.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(description = "Assign a role to a user within a tenant")
public class AssignRoleRequest {

    @Schema(description = "UUID of the user receiving the role", example = "550e8400-e29b-41d4-a716-446655440001")
    @NotNull(message = "userId is required")
    private UUID userId;

    @Schema(description = "UUID of the role to assign", example = "550e8400-e29b-41d4-a716-446655440002")
    @NotNull(message = "roleId is required")
    private UUID roleId;

    @Schema(description = "UUID of the tenant within which the role is granted", example = "550e8400-e29b-41d4-a716-446655440003")
    @NotNull(message = "tenantId is required")
    private UUID tenantId;
}
