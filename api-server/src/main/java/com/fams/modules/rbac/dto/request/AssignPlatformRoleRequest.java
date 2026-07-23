package com.fams.modules.rbac.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Issue #10 (docs/issues/ISSUES.md): assigns a platform-scoped role (tenant_id IS NULL, e.g.
 * PLATFORM_STAFF) — separate from {@link AssignRoleRequest} because that one requires a
 * tenant, which a platform-wide assignment deliberately has none of.
 */
@Data
@Schema(description = "Assign a platform-scoped (cross-tenant) system role to a user")
public class AssignPlatformRoleRequest {

    @Schema(description = "UUID of the user receiving the role", example = "550e8400-e29b-41d4-a716-446655440001")
    @NotNull(message = "userId is required")
    private UUID userId;

    @Schema(description = "UUID of the platform-scoped system role to assign (e.g. PLATFORM_STAFF)",
            example = "550e8400-e29b-41d4-a716-446655440002")
    @NotNull(message = "roleId is required")
    private UUID roleId;
}
