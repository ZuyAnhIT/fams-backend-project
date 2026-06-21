package com.fams.modules.rbac.controller;

import com.fams.modules.rbac.dto.request.AssignRoleRequest;
import com.fams.modules.rbac.dto.response.UserRoleResponse;
import com.fams.modules.rbac.service.UserRoleService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/user-roles")
public class UserRoleController {

    private final UserRoleService userRoleService;

    public UserRoleController(UserRoleService userRoleService) {
        this.userRoleService = userRoleService;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserRoleResponse>> assignRole(
            @Valid @RequestBody AssignRoleRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        log.info("Assign role: userId={} roleId={} tenantId={} by={}",
                request.getUserId(), request.getRoleId(), request.getTenantId(),
                userDetails.getUserId());

        UserRoleResponse result = userRoleService.assignRole(
                userDetails.getUserId(), userDetails.isPlatformAdmin(), request);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> revokeRole(
            @PathVariable java.util.UUID id,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        log.info("Revoke role assignment: id={} by={}", id, userDetails.getUserId());

        userRoleService.revokeRole(id, userDetails.getUserId(), userDetails.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
