package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request to resend the email verification link (Issue #2, docs/issues/ISSUES.md)")
public class ResendVerificationRequest {

    @Schema(description = "Email address to resend the verification link to", example = "alice@acme.com")
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;
}
