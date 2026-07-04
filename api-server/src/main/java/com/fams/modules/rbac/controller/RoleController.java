package com.fams.modules.rbac.controller;

import com.fams.modules.rbac.dto.request.CreateRoleRequest;
import com.fams.modules.rbac.dto.request.UpdateRoleRequest;
import com.fams.modules.rbac.dto.response.MyRoleResponse;
import com.fams.modules.rbac.dto.response.RoleDetailResponse;
import com.fams.modules.rbac.dto.response.RoleResponse;
import com.fams.modules.rbac.service.RoleService;
import com.fams.modules.rbac.service.UserRoleService;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "Roles", description = "RBAC role management — create, list, update, and delete tenant roles")
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final RoleService roleService;
    private final UserRoleService userRoleService;

    public RoleController(RoleService roleService, UserRoleService userRoleService) {
        this.roleService = roleService;
        this.userRoleService = userRoleService;
    }

    @Operation(
        summary = "Get my roles",
        description = "Returns all role assignments for the currently authenticated user, " +
                      "including the full list of permissions granted by each role."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Roles returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<MyRoleResponse>>> getMyRoles(
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get my roles: userId={}", userDetails.getUserId());
        List<MyRoleResponse> roles = userRoleService.getCurrentUserRoles(userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(roles));
    }

    @Operation(summary = "List roles",
        description = "Returns a paginated list of roles. Platform Admins see all roles; tenant users see only their tenant's roles. Supports search, isSystem filter, and sorting.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Roles returned",
            content = @Content(schema = @Schema(implementation = RoleResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PageResponse<RoleResponse>>> listRoles(
            @Parameter(description = "Filter by tenant ID (Platform Admin only)") @RequestParam(required = false) UUID tenantId,
            @Parameter(description = "Search by role name") @RequestParam(required = false) String search,
            @Parameter(description = "Filter system roles (true/false)") @RequestParam(required = false) Boolean isSystem,
            @Parameter(description = "Sort field") @RequestParam(defaultValue = "isSystem") String sortBy,
            @Parameter(description = "Sort direction: asc or desc") @RequestParam(defaultValue = "desc") String sortDir,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (1–100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        log.info("List roles: tenantId={} search={} isSystem={} page={} size={} by userId={}",
                tenantId, search, isSystem, page, size, userDetails.getUserId());

        PageResponse<RoleResponse> result = roleService.listRoles(
                tenantId, search, isSystem, sortBy, sortDir, page, size,
                userDetails.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(summary = "Get role details",
        description = "Returns full details of a role including its complete permissions list. Any authenticated user may view system roles. For custom (tenant) roles, the caller must belong to the same tenant and hold roles:read or roles:update permission — Platform Admins may view any role.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Role details returned",
            content = @Content(schema = @Schema(implementation = RoleDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Role not found")
    })
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<RoleDetailResponse>> getRole(
            @Parameter(description = "Role UUID") @PathVariable UUID id,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        log.info("Get role: id={} by userId={}", id, userDetails.getUserId());

        RoleDetailResponse result = roleService.getRoleById(id, userDetails.getUserId(), userDetails.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(summary = "Create a custom role",
        description = "Creates a new role for a tenant and optionally assigns a set of permissions. Callable by Company Admins and Platform Admins.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Role created",
            content = @Content(schema = @Schema(implementation = RoleDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Role name already exists in this tenant")
    })
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<RoleDetailResponse>> createRole(
            @Valid @RequestBody CreateRoleRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        log.info("Create role: tenantId={} name={} by userId={}",
                request.getTenantId(), request.getName(), userDetails.getUserId());

        RoleDetailResponse result = roleService.createRole(
                userDetails.getUserId(), userDetails.isPlatformAdmin(), request);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }

    @Operation(summary = "Update a role",
        description = "Updates the name, description and/or permission list of a custom (non-system) role. Only the owning tenant's admin or a Platform Admin may update a role.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Role updated",
            content = @Content(schema = @Schema(implementation = RoleDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions or attempt to modify a system role"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Role not found")
    })
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<RoleDetailResponse>> updateRole(
            @Parameter(description = "Role UUID") @PathVariable UUID id,
            @Valid @RequestBody UpdateRoleRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        log.info("Update role: id={} name={} by userId={}", id, request.getName(), userDetails.getUserId());

        RoleDetailResponse result = roleService.updateRole(
                id, userDetails.getUserId(), userDetails.isPlatformAdmin(), request);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(summary = "Delete or deactivate a role",
        description = "Soft-deletes a custom role. System roles cannot be deleted. Only the owning tenant's admin or a Platform Admin may call this endpoint.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Role deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions or attempt to delete a system role"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Role not found")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> deleteRole(
            @Parameter(description = "Role UUID") @PathVariable UUID id,
            @AuthenticationPrincipal FamsUserDetails userDetails) {

        log.info("Delete role: id={} by userId={}", id, userDetails.getUserId());

        roleService.deleteRole(id, userDetails.getUserId(), userDetails.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
