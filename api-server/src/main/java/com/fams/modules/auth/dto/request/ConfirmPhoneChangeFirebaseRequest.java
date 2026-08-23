package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Confirm phone number change using a Firebase Phone Auth ID token")
public class ConfirmPhoneChangeFirebaseRequest {

    @Schema(description = "Firebase ID token obtained after completing phone OTP verification client-side")
    @NotBlank(message = "Firebase ID token is required")
    private String firebaseIdToken;
}
