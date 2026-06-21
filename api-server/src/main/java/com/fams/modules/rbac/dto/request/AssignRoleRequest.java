package com.fams.modules.rbac.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AssignRoleRequest {

    @NotNull(message = "userId is required")
    private UUID userId;

    @NotNull(message = "roleId is required")
    private UUID roleId;

    @NotNull(message = "tenantId is required")
    private UUID tenantId;
}
