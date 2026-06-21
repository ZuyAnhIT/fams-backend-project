package com.fams.modules.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

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

    @Schema(description = "Phone number in E.164 format", example = "+84912345678")
    private String phone;

    @Schema(description = "Display name shown in the UI", example = "Alice Nguyen")
    private String displayName;

    @Schema(description = "URL of the user's avatar image", example = "https://cdn.example.com/avatars/alice.png")
    private String avatarUrl;

    @Schema(description = "Whether the account is active")
    private boolean isActive;

    @Schema(description = "Account creation timestamp (UTC)")
    private OffsetDateTime createdAt;

    @Schema(description = "Last profile update timestamp (UTC)")
    private OffsetDateTime updatedAt;
}
