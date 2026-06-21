package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Login using a Google ID token obtained from Google Sign-In")
public class GoogleLoginRequest {

    @Schema(description = "Google ID token from the client-side Google Sign-In flow", example = "eyJhbGciOiJSUzI1NiJ9...")
    @NotBlank(message = "idToken is required")
    private String idToken;

    @Schema(description = "Unique device identifier for multi-device token tracking", example = "device-abc-123")
    private String deviceId;
}
