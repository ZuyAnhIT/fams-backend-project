package com.fams.modules.rbac.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class PlatformInvitationResponse {

    @Schema(description = "Invitation UUID")
    private UUID id;

    @Schema(description = "Invited email address")
    private String email;

    @Schema(description = "Invitation status: pending, accepted, cancelled, expired")
    private String status;

    @Schema(description = "User ID who sent the invitation")
    private UUID invitedBy;

    @Schema(description = "Platform-scoped role to assign on acceptance")
    private UUID roleId;

    @Schema(description = "Pre-filled first name")
    private String firstName;

    @Schema(description = "Pre-filled last name")
    private String lastName;

    @Schema(description = "Invitation expiry timestamp (UTC)")
    private OffsetDateTime expiresAt;

    @Schema(description = "Creation timestamp (UTC)")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp (UTC)")
    private OffsetDateTime updatedAt;

    @Schema(description = "Invitation token UUID, used to accept the invitation. Present ONLY in the response to "
            + "POST (creation) — always null on list/cancel, since it's a bearer credential (see rbac-api.md).")
    private UUID token;
}
