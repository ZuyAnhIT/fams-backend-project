package com.fams.modules.workspace.controller;

import com.fams.modules.workspace.dto.request.CreateWorkspaceRequest;
import com.fams.modules.workspace.dto.request.UpdateWorkspaceRequest;
import com.fams.modules.workspace.dto.response.WorkspaceResponse;
import com.fams.modules.workspace.dto.response.WorkspaceTreeResponse;
import com.fams.modules.workspace.service.WorkspaceService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "Workspaces", description = "Workspace (department / team) management within a tenant")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Operation(
        summary = "Create workspace",
        description = "Creates a new department or team within the tenant to organise personnel. " +
                      "Workspace names are case-insensitively unique per tenant. " +
                      "An optional parentId enables hierarchical structures. " +
                      "Requires workspaces:create permission. Callable by TENANT_ADMIN."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
            description = "Workspace created",
            content = @Content(schema = @Schema(implementation = WorkspaceResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant or parent workspace not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "Workspace name already exists in this tenant")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<WorkspaceResponse>> createWorkspace(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Valid @RequestBody CreateWorkspaceRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Create workspace tenantId={} by={}", tenantId, userDetails.getUserId());
        WorkspaceResponse response = workspaceService.createWorkspace(
                tenantId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(
        summary = "List workspaces (paginated)",
        description = "Returns a paginated flat list of workspaces with optional search (name) and " +
                      "status / type filters. Use the /tree endpoint for the hierarchical view. " +
                      "Requires workspaces:list permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Workspace list returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant not found")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<WorkspaceResponse>>> listWorkspaces(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Search by name (case-insensitive contains)") @RequestParam(required = false) String search,
            @Parameter(description = "Filter by status: active | inactive") @RequestParam(required = false) String status,
            @Parameter(description = "Filter by type: department | team") @RequestParam(required = false) String type,
            @Parameter(description = "Sort field") @RequestParam(defaultValue = "name") String sortBy,
            @Parameter(description = "Sort direction: asc | desc") @RequestParam(defaultValue = "asc") String sortDir,
            @Parameter(description = "Page index (0-based)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("List workspaces tenantId={} by={}", tenantId, userDetails.getUserId());
        PageResponse<WorkspaceResponse> response = workspaceService.listWorkspaces(
                tenantId, search, status, type, sortBy, sortDir, page, size,
                userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Get workspace tree",
        description = "Returns all workspaces as a nested tree. Supports optional search (by name) and " +
                      "status filter. When a search term is provided, matching nodes and their ancestors " +
                      "are included to preserve tree context. " +
                      "Requires workspaces:list permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Workspace tree returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant not found")
    })
    @GetMapping("/tree")
    public ResponseEntity<ApiResponse<List<WorkspaceTreeResponse>>> getWorkspaceTree(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Search by name (matching nodes and their ancestors are included)") @RequestParam(required = false) String search,
            @Parameter(description = "Filter by status: active | inactive") @RequestParam(required = false) String status,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get workspace tree tenantId={} by={}", tenantId, userDetails.getUserId());
        List<WorkspaceTreeResponse> response = workspaceService.getWorkspaceTree(
                tenantId, search, status, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Update workspace",
        description = "Partially updates a workspace. All fields are optional — only provided fields are changed. " +
                      "Name changes are case-insensitively unique-checked. " +
                      "Set clearParent=true to remove the parent and make this a root workspace. " +
                      "Circular parent references are rejected. " +
                      "Requires workspaces:update permission. Callable by TENANT_ADMIN."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Workspace updated",
            content = @Content(schema = @Schema(implementation = WorkspaceResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error or circular parent reference"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant, workspace, or parent workspace not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "Workspace name already exists in this tenant")
    })
    @PutMapping("/{workspaceId}")
    public ResponseEntity<ApiResponse<WorkspaceResponse>> updateWorkspace(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Workspace UUID") @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateWorkspaceRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update workspace workspaceId={} tenantId={} by={}", workspaceId, tenantId, userDetails.getUserId());
        WorkspaceResponse response = workspaceService.updateWorkspace(
                tenantId, workspaceId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Get workspace",
        description = "Returns details of a single workspace by ID. " +
                      "Requires workspaces:read permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Workspace found",
            content = @Content(schema = @Schema(implementation = WorkspaceResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant or workspace not found")
    })
    @GetMapping("/{workspaceId}")
    public ResponseEntity<ApiResponse<WorkspaceResponse>> getWorkspace(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Workspace UUID") @PathVariable UUID workspaceId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get workspace workspaceId={} tenantId={} by={}", workspaceId, tenantId, userDetails.getUserId());
        WorkspaceResponse response = workspaceService.getWorkspace(
                tenantId, workspaceId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Delete workspace",
        description = "Soft-deletes a workspace. Blocked if it still has active members or active child "
            + "workspaces — remove/transfer all members and reparent/delete children first. "
            + "Requires workspaces:delete permission. Callable by TENANT_ADMIN."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Workspace deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Workspace still has active members or child workspaces"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant or workspace not found")
    })
    @DeleteMapping("/{workspaceId}")
    public ResponseEntity<ApiResponse<Void>> deleteWorkspace(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Workspace UUID") @PathVariable UUID workspaceId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Delete workspace workspaceId={} tenantId={} by={}", workspaceId, tenantId, userDetails.getUserId());
        workspaceService.deleteWorkspace(tenantId, workspaceId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
