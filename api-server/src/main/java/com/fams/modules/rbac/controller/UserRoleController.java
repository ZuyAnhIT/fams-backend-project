package com.fams.modules.rbac.controller;

import com.fams.modules.rbac.dto.request.AssignPlatformRoleRequest;
import com.fams.modules.rbac.dto.request.AssignRoleRequest;
import com.fams.modules.rbac.dto.request.BulkAssignRoleRequest;
import com.fams.modules.rbac.dto.response.BulkAssignRoleResponse;
import com.fams.modules.rbac.dto.response.UserRoleResponse;
import com.fams.modules.rbac.service.UserRoleService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "User Roles", description = "Assign and revoke roles for users within a tenant")
@RestController
@RequestMapping("/api/v1/user-roles")
public class UserRoleController {

    private final UserRoleService userRoleService;

    public UserRoleController(UserRoleService userRoleService) {
        this.userRoleService = userRoleService;
    }

    @Operation(summary = "Assign a role to a user",
        description = "Grants the specified role to a user within a tenant. Callable by Company Admins and Platform Admins.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Role assigned",
            content = @Content(schema = @Schema(implementation = UserRoleResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User, role, or tenant not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "User already has this role in the tenant")
    })
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

    @Operation(summary = "Assign a role to many users at once",
        description = "Grants one role to a list of users within a tenant in a single call — e.g. moving every "
            + "employee off a role that's being retired onto its replacement (pass revokeRoleId to also revoke "
            + "that old role from each user first). Partial failure is normal: one bad entry (already has the "
            + "role, user not found, ...) does not roll back the others — check the per-user results.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Batch processed (see per-user results for outcome)",
            content = @Content(schema = @Schema(implementation = BulkAssignRoleResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @PostMapping("/bulk-assign")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<BulkAssignRoleResponse>> bulkAssignRole(
            @Valid @RequestBody BulkAssignRoleRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        log.info("Bulk assign role: roleId={} revokeRoleId={} tenantId={} userCount={} by={}",
                request.getRoleId(), request.getRevokeRoleId(), request.getTenantId(),
                request.getUserIds().size(), userDetails.getUserId());

        BulkAssignRoleResponse result = userRoleService.bulkAssignRole(
                userDetails.getUserId(), userDetails.isPlatformAdmin(), request);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(summary = "Assign a platform-scoped (cross-tenant) system role",
        description = "Issue #10 (docs/issues/ISSUES.md): grants a platform-wide system role (e.g. PLATFORM_STAFF) — " +
            "no tenant context, applies across the whole platform. Platform Admins only.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Platform role assigned",
            content = @Content(schema = @Schema(implementation = UserRoleResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error, or role is tenant-owned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User or role not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "User already has this platform role")
    })
    @PostMapping("/platform")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<UserRoleResponse>> assignPlatformRole(
            @Valid @RequestBody AssignPlatformRoleRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        log.info("Assign platform role: userId={} roleId={} by={}",
                request.getUserId(), request.getRoleId(), userDetails.getUserId());

        UserRoleResponse result = userRoleService.assignPlatformRole(userDetails.getUserId(), request);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }

    @Operation(summary = "Revoke a role from a user",
        description = "Removes the user-role assignment identified by its UUID. Callable by Company Admins and Platform Admins.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Role revoked"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Assignment not found")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> revokeRole(
            @Parameter(description = "User-role assignment UUID") @PathVariable java.util.UUID id,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        log.info("Revoke role assignment: id={} by={}", id, userDetails.getUserId());

        userRoleService.revokeRole(id, userDetails.getUserId(), userDetails.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
