package com.fams.modules.rbac.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "Create a new custom role within a tenant")
public class CreateRoleRequest {

    @Schema(description = "ID of the tenant that owns this role", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull(message = "Tenant ID is required")
    private UUID tenantId;

    @Schema(description = "Unique role name within the tenant (1–100 chars)", example = "Site Supervisor")
    @NotBlank(message = "Role name is required")
    @Size(min = 1, max = 100, message = "Role name must be between 1 and 100 characters")
    private String name;

    @Schema(description = "Optional role description (max 500 chars)", example = "Manages a single construction site")
    @Size(max = 500, message = "Description must be at most 500 characters")
    private String description;

    @Schema(description = "List of permission UUIDs to assign to this role. Omit or pass an empty list for no permissions.")
    private List<UUID> permissionIds;
}
