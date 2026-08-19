package com.fams.modules.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Authenticated user's profile information")
public class UserProfileResponse {

    @Schema(description = "User UUID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "Email address", example = "alice@acme.com")
    private String email;

    @Schema(description = "Whether the email address has been verified (a freshly added/changed email is false until the link is clicked)")
    private boolean emailVerified;

    @Schema(description = "Phone number in E.164 format", example = "+84912345678")
    private String phone;

    @Schema(description = "Whether the phone number has been verified via OTP")
    private boolean phoneVerified;

    @Schema(description = "Display name shown in the UI", example = "Alice Nguyen")
    private String displayName;

    @Schema(description = "URL of the user's avatar image", example = "https://cdn.example.com/avatars/alice.png")
    private String avatarUrl;

    @Schema(description = "Date of birth (Issue #4, docs/issues/ISSUES.md)", example = "1995-04-12")
    private LocalDate dateOfBirth;

    @Schema(description = "Hometown / quê quán", example = "Nghệ An")
    private String hometown;

    @Schema(description = "Gender — free-text (e.g. male/female/other), not constrained to a fixed list", example = "female")
    private String gender;

    @Schema(description = "Home address", example = "123 Nguyễn Trãi, Q.1, TP.HCM")
    private String address;

    @Schema(description = "Whether a Google account is linked for one-click login (Issue #7, docs/issues/ISSUES.md)")
    private boolean googleLinked;

    @Schema(description = "Whether the account is active")
    private boolean isActive;

    @Schema(description = "True if this account holds platform-wide admin access (visible to the account owner about themselves, and to Platform Admins browsing the user directory)")
    private boolean isPlatformAdmin;

    @Schema(description = "Whether TOTP 2FA is enabled for this account (#10, 2026-08-19)")
    private boolean totpEnabled;

    @Schema(description = "Primary tenant UUID — the same one a fresh login would place this user "
            + "in (via PrimaryRoleResolver). Null if the user holds no active tenant role anywhere "
            + "(#10, 2026-08-19)", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID currentTenantId;

    @Schema(description = "Primary tenant's display name, null if currentTenantId is null (#10, 2026-08-19)", example = "Acme Corp")
    private String currentTenantName;

    @Schema(description = "Role name held in the primary tenant, null if currentTenantId is null (#10, 2026-08-19)", example = "HR_MANAGER")
    private String currentTenantRole;

    @Schema(description = "Last successful login timestamp (UTC), null if never logged in")
    private OffsetDateTime lastLoginAt;

    @Schema(description = "Account creation timestamp (UTC)")
    private OffsetDateTime createdAt;

    @Schema(description = "Last profile update timestamp (UTC)")
    private OffsetDateTime updatedAt;
}
