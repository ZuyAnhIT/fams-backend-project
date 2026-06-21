package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Update the authenticated user's profile fields. All fields are optional.")
public class UpdateProfileRequest {

    @Schema(description = "New display name", example = "Alice Nguyen")
    @Size(min = 1, max = 100, message = "Display name must be between 1 and 100 characters")
    private String displayName;

    @Schema(description = "New phone number in E.164 format", example = "+84912345678")
    @Pattern(regexp = "^\\+?[1-9]\\d{7,14}$", message = "Invalid phone number format")
    private String phone;

    @Schema(description = "URL of the user's avatar image (max 500 chars)", example = "https://cdn.example.com/avatars/alice.png")
    @Size(max = 500, message = "Avatar URL must not exceed 500 characters")
    private String avatarUrl;
}
