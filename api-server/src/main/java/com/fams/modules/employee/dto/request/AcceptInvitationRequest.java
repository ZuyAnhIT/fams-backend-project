package com.fams.modules.employee.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class AcceptInvitationRequest {

    @Schema(description = "Invitation token from the email link", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull(message = "Token is required")
    private UUID token;

    @Schema(description = "Password for the new account (min 8 chars). Required only when no account exists for the invited email.",
            example = "SecureP@ss1")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @Schema(description = "Display name for the new account. Defaults to the name pre-filled in the invitation if omitted.",
            example = "John Doe")
    private String displayName;

    @Schema(description = "Device identifier for multi-device JWT tracking", example = "mobile-ios-01")
    private String deviceId;
}
