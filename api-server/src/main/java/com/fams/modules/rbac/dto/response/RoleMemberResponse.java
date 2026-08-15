package com.fams.modules.rbac.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "One user currently holding a given role")
public class RoleMemberResponse {

    @Schema(description = "User-role assignment UUID — pass to DELETE /user-roles/{id} to revoke")
    private UUID userRoleId;

    @Schema(description = "User UUID")
    private UUID userId;

    @Schema(description = "Display name")
    private String displayName;

    @Schema(description = "Email or phone, whichever the account has")
    private String contact;

    @Schema(description = "When this role was assigned")
    private OffsetDateTime assignedAt;

    @Schema(description = "If non-empty, this assignment is restricted to these sites only")
    private List<UUID> siteIds;
}
