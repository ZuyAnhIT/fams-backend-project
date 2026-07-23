package com.fams.modules.rbac.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "A role assignment held by the currently authenticated user")
public class MyRoleResponse {

    @Schema(description = "Assignment UUID")
    private UUID id;

    @Schema(description = "UUID of the user who holds the role")
    private UUID userId;

    @Schema(description = "UUID of the role")
    private UUID roleId;

    @Schema(description = "Role name", example = "TENANT_ADMIN")
    private String roleName;

    @Schema(description = "Tenant within which the role is effective, or null for a platform-scoped role")
    private UUID tenantId;

    @Schema(description = "Tenant display name, for building a company switcher; null for a platform-scoped role",
            example = "Công ty CP Xây dựng Hoàng Long")
    private String tenantName;

    @Schema(description = "Tenant slug; null for a platform-scoped role", example = "acme-corp")
    private String tenantSlug;

    @Schema(description = "Timestamp when the role was assigned")
    private OffsetDateTime assignedAt;

    @Schema(description = "List of permission names granted by this role", example = "[\"roles:create\",\"employees:read\"]")
    private List<String> permissions;
}
