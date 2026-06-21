package com.fams.modules.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Result of account registration")
public class RegisterResponse {

    @Schema(description = "True when an email verification link was sent and the user must verify before logging in")
    private boolean emailVerificationRequired;

    @Schema(description = "Human-readable status message", example = "Registration successful. Please check your email to verify your account.")
    private String message;

    @Schema(description = "JWT access token (only present for phone-only registration where email verification is not required)", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String accessToken;

    @Schema(description = "Refresh token (only present for phone-only registration)", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String refreshToken;

    @Schema(description = "Token type, always Bearer", example = "Bearer")
    private String tokenType;

    @Schema(description = "Access token lifetime in seconds (only present for phone-only registration)", example = "900")
    private Long expiresIn;
}
