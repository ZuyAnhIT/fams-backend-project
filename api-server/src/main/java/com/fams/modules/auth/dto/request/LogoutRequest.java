package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Logout from the current device")
public class LogoutRequest {

    @Schema(description = "The refresh token issued at login that should be invalidated", example = "eyJhbGciOiJIUzI1NiJ9...")
    @NotBlank(message = "Refresh token is required")
    private String refreshToken;
}
