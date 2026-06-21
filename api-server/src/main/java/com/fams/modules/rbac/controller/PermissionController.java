package com.fams.modules.rbac.controller;

import com.fams.modules.rbac.dto.response.PermissionGroupResponse;
import com.fams.modules.rbac.service.PermissionService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@Tag(name = "Permissions", description = "Read-only access to the system permission catalogue")
@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Operation(summary = "List permissions grouped by resource",
        description = "Returns all system permissions organised into resource groups. Accessible to any authenticated user so that Company Admins can configure roles.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Permission groups returned",
            content = @Content(schema = @Schema(implementation = PermissionGroupResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<PermissionGroupResponse>>> listGrouped(
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        log.info("List permissions grouped by userId={}", userDetails.getUserId());

        List<PermissionGroupResponse> result = permissionService.listGrouped();
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
