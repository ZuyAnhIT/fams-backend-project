package com.fams.modules.rbac.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.UUID;

@Data
public class InvitePlatformStaffRequest {

    @Schema(description = "Email address to invite as platform staff", example = "newstaff@fams.com")
    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email address")
    private String email;

    @Schema(description = "First name (optional, pre-fills the account if this becomes a brand-new user)", example = "An")
    private String firstName;

    @Schema(description = "Last name (optional)", example = "Nguyen")
    private String lastName;

    @Schema(description = "Platform-scoped role UUID to assign on acceptance (system role like PLATFORM_STAFF, or "
            + "a platform-scoped custom role created via POST /roles with no tenantId). Omit to default to "
            + "PLATFORM_STAFF.")
    private UUID roleId;
}
