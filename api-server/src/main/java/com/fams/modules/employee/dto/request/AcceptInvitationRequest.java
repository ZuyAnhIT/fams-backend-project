package com.fams.modules.employee.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class AcceptInvitationRequest {

    @Schema(description = "Invitation token from the email link", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull(message = "Token is required")
    private UUID token;

    @Schema(description = "Password for the new account (min 8 chars). Required when creating a brand-new account.",
            example = "SecureP@ss1")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @Schema(description = "Display name for the new account. Defaults to the name pre-filled in the invitation if omitted.",
            example = "John Doe")
    private String displayName;

    @Schema(description = "Device identifier for multi-device JWT tracking", example = "mobile-ios-01")
    private String deviceId;

    @Schema(description = "Phone number of an existing phone-based account to link this invitation's email to. " +
            "Provide together with existingPassword to merge the invited email into an existing account " +
            "instead of creating a new one.",
            example = "0987654321")
    @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "Phone must be 7-15 digits, optionally prefixed with +")
    private String existingPhone;

    @Schema(description = "Password of the existing phone-based account. Required when existingPhone is provided.",
            example = "MyOldP@ss1")
    private String existingPassword;
}
