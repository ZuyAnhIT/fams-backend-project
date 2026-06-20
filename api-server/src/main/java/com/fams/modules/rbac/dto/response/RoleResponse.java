package com.fams.modules.rbac.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class RoleResponse {
    private UUID id;
    private String name;
    private String description;
    private boolean isSystem;
    private UUID tenantId;
    private int permissionCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
