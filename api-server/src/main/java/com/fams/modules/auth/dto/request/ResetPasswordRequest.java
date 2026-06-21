package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Set a new password using the token from the reset email")
public class ResetPasswordRequest {

    @Schema(description = "Password-reset token received by email", example = "eyJhbGciOiJIUzI1NiJ9...")
    @NotBlank(message = "Reset token is required")
    private String token;

    @Schema(description = "New password — min 8 chars, must contain upper, lower, and digit", example = "NewP@ss99")
    @NotBlank(message = "New password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
            message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit"
    )
    private String newPassword;
}
