package com.fams.modules.rbac.controller;

import com.fams.modules.rbac.dto.response.RoleResponse;
import com.fams.modules.rbac.service.RoleService;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PageResponse<RoleResponse>>> listRoles(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean isSystem,
            @RequestParam(defaultValue = "isSystem") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        log.info("List roles: tenantId={} search={} isSystem={} page={} size={} by userId={}",
                tenantId, search, isSystem, page, size, userDetails.getUserId());

        PageResponse<RoleResponse> result = roleService.listRoles(
                tenantId, search, isSystem, sortBy, sortDir, page, size,
                userDetails.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
