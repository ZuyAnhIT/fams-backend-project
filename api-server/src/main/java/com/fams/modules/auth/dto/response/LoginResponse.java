package com.fams.modules.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String accessToken;
    private String refreshToken;

    @Builder.Default
    private String tokenType = "Bearer";

    private long expiresIn;

    // True when the account has TOTP enabled; tokens are null until TOTP is verified
    @Builder.Default
    private boolean totpRequired = false;

    // Temporary token exchanged for real tokens after TOTP verification
    private String pendingToken;
}
