package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Email/password login request")
public class LoginRequest {

    @Schema(description = "User email address", example = "alice@acme.com")
    @NotBlank
    @Email
    private String email;

    @Schema(description = "Account password (min 8 chars)", example = "S3cur3P@ss")
    @NotBlank
    @Size(min = 8)
    private String password;

    @Schema(description = "Unique device identifier for multi-device token tracking", example = "device-abc-123")
    private String deviceId;
}
