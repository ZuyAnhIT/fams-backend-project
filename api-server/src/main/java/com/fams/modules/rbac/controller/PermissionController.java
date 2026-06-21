package com.fams.modules.rbac.controller;

import com.fams.modules.rbac.dto.response.PermissionGroupResponse;
import com.fams.modules.rbac.service.PermissionService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<PermissionGroupResponse>>> listGrouped(
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        log.info("List permissions grouped by userId={}", userDetails.getUserId());

        List<PermissionGroupResponse> result = permissionService.listGrouped();
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
