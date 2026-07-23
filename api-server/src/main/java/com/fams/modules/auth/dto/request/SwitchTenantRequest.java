package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Issue #3 (docs/issues/ISSUES.md): switch the authenticated user's active company. Requires
 * the current refresh token so the switch can be persisted onto that session and survive
 * subsequent token refreshes (see {@code RefreshToken.activeTenantId}).
 */
@Data
@Schema(description = "Switch the current session's active tenant (company) for a multi-tenant user")
public class SwitchTenantRequest {

    @Schema(description = "UUID of the tenant to switch to — caller must hold an active role there",
            example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull(message = "tenantId is required")
    private UUID tenantId;

    @Schema(description = "Current refresh token, to persist the switch on this session")
    @NotBlank(message = "refreshToken is required")
    private String refreshToken;
}
