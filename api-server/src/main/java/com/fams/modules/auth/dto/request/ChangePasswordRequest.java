package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Change the authenticated user's password")
public class ChangePasswordRequest {

    @Schema(description = "Current (old) password for verification", example = "OldP@ss99")
    @NotBlank(message = "Current password is required")
    private String currentPassword;

    @Schema(description = "New password — min 8 chars, must contain upper, lower, and digit", example = "NewP@ss99")
    @NotBlank(message = "New password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
            message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit"
    )
    private String newPassword;
}
