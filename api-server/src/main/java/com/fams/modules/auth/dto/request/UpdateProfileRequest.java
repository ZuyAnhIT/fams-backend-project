package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Update the authenticated user's profile fields. All fields are optional.")
public class UpdateProfileRequest {

    @Schema(description = "New display name", example = "Alice Nguyen")
    @Size(min = 1, max = 100, message = "Display name must be between 1 and 100 characters")
    private String displayName;

    @Schema(description = "New phone number in E.164 format", example = "+84912345678")
    @Pattern(regexp = "^\\+?[1-9]\\d{7,14}$", message = "Invalid phone number format")
    private String phone;

    @Schema(description = "URL of the user's avatar image (max 500 chars). To upload an actual image file " +
            "instead of linking an existing URL, use POST /auth/profile/avatar.",
            example = "https://cdn.example.com/avatars/alice.png")
    @Size(max = 500, message = "Avatar URL must not exceed 500 characters")
    private String avatarUrl;

    @Schema(description = "Date of birth — must be in the past (Issue #4, docs/issues/ISSUES.md)", example = "1995-04-12")
    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;

    @Schema(description = "Hometown / quê quán (max 255 chars)", example = "Nghệ An")
    @Size(max = 255, message = "Hometown must not exceed 255 characters")
    private String hometown;

    @Schema(description = "Gender — free text, not constrained to a fixed list (max 20 chars)", example = "female")
    @Size(max = 20, message = "Gender must not exceed 20 characters")
    private String gender;

    @Schema(description = "Home address (max 500 chars)", example = "123 Nguyễn Trãi, Q.1, TP.HCM")
    @Size(max = 500, message = "Address must not exceed 500 characters")
    private String address;
}
