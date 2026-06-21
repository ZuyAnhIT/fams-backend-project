package com.fams.modules.rbac.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class UserRoleResponse {
    private UUID id;
    private UUID userId;
    private UUID roleId;
    private String roleName;
    private UUID tenantId;
    private UUID assignedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
