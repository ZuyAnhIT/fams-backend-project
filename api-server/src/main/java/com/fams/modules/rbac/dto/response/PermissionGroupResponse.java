package com.fams.modules.rbac.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PermissionGroupResponse {
    private String resource;
    private int permissionCount;
    private List<PermissionResponse> permissions;
}
