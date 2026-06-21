package com.fams.modules.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "JWT token pair returned on successful login")
public class LoginResponse {

    @Schema(description = "Short-lived JWT access token (15 min)", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String accessToken;

    @Schema(description = "Long-lived refresh token used to obtain a new access token", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String refreshToken;

    @Schema(description = "Token type, always Bearer", example = "Bearer")
    @Builder.Default
    private String tokenType = "Bearer";

    @Schema(description = "Access token lifetime in seconds", example = "900")
    private long expiresIn;

    @Schema(description = "When true the account has TOTP enabled; accessToken/refreshToken are null and pendingToken is set")
    @Builder.Default
    private boolean totpRequired = false;

    @Schema(description = "Short-lived token exchanged for real tokens after TOTP verification (only present when totpRequired=true)", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String pendingToken;
}
