package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "New account registration request. Provide at least one of email or phone.")
public class RegisterRequest {

    @Schema(description = "Email address (required if phone is omitted)", example = "alice@acme.com")
    @Email(message = "Invalid email format")
    private String email;

    @Schema(description = "Phone number in E.164 format (required if email is omitted)", example = "+84912345678")
    @Pattern(regexp = "^\\+?[1-9]\\d{7,14}$", message = "Invalid phone number format")
    private String phone;

    @Schema(description = "Password — min 8 chars, must contain upper, lower, and digit", example = "S3cur3P@ss")
    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
            message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit"
    )
    private String password;

    @Schema(description = "Full display name shown in the UI", example = "Alice Nguyen")
    @NotBlank(message = "Display name is required")
    @Size(max = 100, message = "Display name must not exceed 100 characters")
    private String displayName;

    @Schema(description = "Unique device identifier for multi-device token tracking", example = "device-abc-123")
    private String deviceId;

    @Schema(description = "Firebase phone-auth ID token proving OTP verification. Required when registering " +
            "with `phone` and no `email` — the client must complete Firebase's phone OTP flow first and pass " +
            "the resulting ID token here; the phone number inside the token must match `phone`.",
            example = "eyJhbGciOiJSUzI1NiIsI...")
    private String firebaseIdToken;
}
