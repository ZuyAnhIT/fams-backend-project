package com.fams.modules.rbac.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(description = "Clone an existing role (system or custom) into a new, independent custom role — "
        + "pre-fills the new role's permissions from the source instead of ticking them one by one.")
public class CloneRoleRequest {

    @Schema(description = "Name for the new role (must be unique within its scope)", example = "Nhân viên — Công ty A")
    @NotBlank(message = "Role name is required")
    @Size(min = 1, max = 100, message = "Role name must be between 1 and 100 characters")
    private String name;

    @Schema(description = "Optional description for the new role. Defaults to the source role's description if omitted.")
    @Size(max = 500, message = "Description must be at most 500 characters")
    private String description;

    @Schema(description = "Tenant to own the new role. Omit to create a PLATFORM-scoped clone instead "
            + "(Platform Admin only).", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID tenantId;
}
