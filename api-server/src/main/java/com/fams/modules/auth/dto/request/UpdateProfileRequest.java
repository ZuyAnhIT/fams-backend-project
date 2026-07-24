package com.fams.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "Update the authenticated user's profile fields. All fields are optional. Email, phone, and "
        + "avatar are NOT included here. Email/phone require proof of ownership first, see "
        + "POST /auth/profile/email/request-change and POST /auth/profile/phone/request-change. Avatar must be "
        + "an uploaded file, not a pasted URL — see POST /auth/profile/avatar (upload) and "
        + "DELETE /auth/profile/avatar (remove).")
public class UpdateProfileRequest {

    @Schema(description = "New display name", example = "Alice Nguyen")
    @Size(min = 1, max = 100, message = "Display name must be between 1 and 100 characters")
    private String displayName;

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
