package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Exchange a Firebase Phone Auth ID token for FAMS JWT tokens")
public class FirebasePhoneLoginRequest {

    @Schema(
            description = "Firebase ID Token obtained after the mobile/web app completes phone verification with Firebase",
            example = "eyJhbGciOiJSUzI1NiJ9...")
    @NotBlank(message = "Firebase ID token is required")
    private String firebaseIdToken;

    @Schema(description = "Unique device identifier for multi-device token tracking", example = "device-abc-123")
    private String deviceId;
}
