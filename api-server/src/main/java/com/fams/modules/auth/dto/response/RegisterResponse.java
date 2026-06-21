package com.fams.modules.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RegisterResponse {
    private boolean emailVerificationRequired;
    private String message;
    // Only populated for phone-only registration (no email verification needed)
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private Long expiresIn;
}
