package com.fams.modules.employee.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class InvitationResponse {

    @Schema(description = "Invitation UUID")
    private UUID id;

    @Schema(description = "Tenant the invitation belongs to")
    private UUID tenantId;

    @Schema(description = "Invited email address")
    private String email;

    @Schema(description = "Invitation status: pending, accepted, cancelled, expired")
    private String status;

    @Schema(description = "User ID who sent the invitation")
    private UUID invitedBy;

    @Schema(description = "Role to assign on acceptance")
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

    @Schema(description = "Invitation token UUID (only present on creation, used to accept the invitation)")
    private UUID token;
}
