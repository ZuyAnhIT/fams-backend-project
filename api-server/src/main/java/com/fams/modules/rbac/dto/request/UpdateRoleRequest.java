package com.fams.modules.rbac.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "Update an existing custom role")
public class UpdateRoleRequest {

    @Schema(description = "New role name (1–100 chars)", example = "Senior Site Supervisor")
    @NotBlank(message = "Role name is required")
    @Size(min = 1, max = 100, message = "Role name must be between 1 and 100 characters")
    private String name;

    @Schema(description = "Updated role description (max 500 chars)", example = "Manages multiple sites")
    @Size(max = 500, message = "Description must be at most 500 characters")
    private String description;

    @Schema(description = "Complete replacement list of permission UUIDs. Pass an empty list to remove all permissions.", example = "[\"uuid1\", \"uuid2\"]")
    @NotNull(message = "permissionIds is required (use an empty list to remove all permissions)")
    private List<UUID> permissionIds;
}
