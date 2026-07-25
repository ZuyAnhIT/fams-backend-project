package com.fams.modules.rbac.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class AcceptPlatformInvitationRequest {

    @Schema(description = "Invitation token from the email link", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull(message = "Token is required")
    private UUID token;

    @Schema(description = "Password for the new account (min 8 chars). Required when the invited email has no existing account.",
            example = "SecureP@ss1")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @Schema(description = "Display name for the new account. Defaults to the name pre-filled in the invitation if omitted.",
            example = "An Nguyen")
    private String displayName;

    @Schema(description = "Device identifier for multi-device JWT tracking", example = "web-chrome-01")
    private String deviceId;
}
