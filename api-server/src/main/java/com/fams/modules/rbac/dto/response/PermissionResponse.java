package com.fams.modules.rbac.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class PermissionResponse {
    private UUID id;
    private String name;
    private String resource;
    private String action;
    private String description;
}
