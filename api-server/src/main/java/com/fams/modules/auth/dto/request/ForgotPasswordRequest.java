package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request a password-reset email")
public class ForgotPasswordRequest {

    @Schema(description = "Email address of the account", example = "alice@acme.com")
    @NotBlank(message = "Email is required")
    @Email(message = "Email format is invalid")
    private String email;
}
