package com.fams.modules.rbac.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "A group of permissions belonging to the same resource")
public class PermissionGroupResponse {

    @Schema(description = "Resource name the permissions apply to", example = "employees")
    private String resource;

    @Schema(description = "Number of permissions in this group", example = "4")
    private int permissionCount;

    @Schema(description = "Individual permissions within this resource group")
    private List<PermissionResponse> permissions;
}
