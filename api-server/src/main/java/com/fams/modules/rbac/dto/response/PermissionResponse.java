package com.fams.modules.rbac.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@Schema(description = "A single permission that can be assigned to a role")
public class PermissionResponse {

    @Schema(description = "Permission UUID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "Dot-separated permission name used in @PreAuthorize checks", example = "employees:create")
    private String name;

    @Schema(description = "Resource the permission applies to", example = "employees")
    private String resource;

    @Schema(description = "Action the permission allows", example = "create")
    private String action;

    @Schema(description = "Human-readable description of what this permission grants", example = "Create a new employee profile")
    private String description;
}
