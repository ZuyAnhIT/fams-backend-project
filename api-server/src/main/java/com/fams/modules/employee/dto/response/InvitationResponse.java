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

    @Schema(description = "Phone number recorded on the invitation (optional)")
    private String phone;

    @Schema(description = "Invitation status: pending, accepted, cancelled, expired")
    private String status;

    @Schema(description = "User ID who sent the invitation")
    private UUID invitedBy;

    @Schema(description = "Role to assign on acceptance")
    private UUID roleId;

    @Schema(description = "Workspace to assign the invitee to (as a WorkspaceMember) on acceptance, if any")
    private UUID workspaceId;

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

    @Schema(description = "User ID who cancelled this invitation, present only if status=cancelled")
    private UUID cancelledBy;

    @Schema(description = "Free-text reason given when cancelling, present only if status=cancelled and a reason was given")
    private String cancelReason;

    @Schema(description = "Timestamp the invitation was cancelled, present only if status=cancelled")
    private OffsetDateTime cancelledAt;

    @Schema(description = "Invitation token UUID, used to accept the invitation. Present ONLY in the response to "
            + "POST /invitations (creation) — always null when this invitation appears anywhere else (list, "
            + "cancel), since the token is a bearer credential and must not be re-exposed after the one response "
            + "the invited person's own email link needs. Copy it out of the create response before discarding.")
    private UUID token;
}
