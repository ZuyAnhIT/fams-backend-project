package com.fams.modules.rbac.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "A user-role assignment record")
public class UserRoleResponse {

    @Schema(description = "Assignment UUID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "UUID of the user who holds the role", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID userId;

    @Schema(description = "UUID of the role that was assigned", example = "550e8400-e29b-41d4-a716-446655440002")
    private UUID roleId;

    @Schema(description = "Human-readable name of the assigned role", example = "Site Supervisor")
    private String roleName;

    @Schema(description = "Tenant within which the role is effective", example = "550e8400-e29b-41d4-a716-446655440003")
    private UUID tenantId;

    @Schema(description = "Sites this assignment is restricted to. Empty means unrestricted — the full tenant.")
    private List<UUID> siteIds;

    @Schema(description = "Same sites as siteIds, with display names resolved — avoids a follow-up GET /sites call just to show scope. Empty means unrestricted.")
    private List<SiteRefResponse> sites;

    @Schema(description = "UUID of the admin who created this assignment", example = "550e8400-e29b-41d4-a716-446655440004")
    private UUID assignedBy;

    @Schema(description = "Assignment creation timestamp (UTC)")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp (UTC)")
    private OffsetDateTime updatedAt;
}
