package com.fams.modules.workspace.controller;

import com.fams.modules.workspace.dto.request.AssignWorkspaceMemberRequest;
import com.fams.modules.workspace.dto.request.TransferWorkspaceMemberRequest;
import com.fams.modules.workspace.dto.response.WorkspaceMemberResponse;
import com.fams.modules.workspace.service.WorkspaceMemberService;
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

import java.util.UUID;

@Slf4j
@Tag(name = "Workspace Members", description = "Assign and manage employee membership within a workspace")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/workspaces/{workspaceId}/members")
public class WorkspaceMemberController {

    private final WorkspaceMemberService memberService;

    public WorkspaceMemberController(WorkspaceMemberService memberService) {
        this.memberService = memberService;
    }

    @Operation(
        summary = "Assign employee to workspace",
        description = "Adds an employee to a workspace with an optional workspace-level role " +
                      "(member | lead | manager, default: member). " +
                      "An employee may only appear once per workspace. " +
                      "Requires workspace_members:create permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
            description = "Employee assigned to workspace",
            content = @Content(schema = @Schema(implementation = WorkspaceMemberResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant, workspace, or employee not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "Employee is already a member of this workspace")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<WorkspaceMemberResponse>> assignMember(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Workspace UUID") @PathVariable UUID workspaceId,
            @Valid @RequestBody AssignWorkspaceMemberRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Assign member workspaceId={} employeeId={} tenantId={} by={}",
                workspaceId, request.getEmployeeId(), tenantId, userDetails.getUserId());
        WorkspaceMemberResponse response = memberService.assignMember(
                tenantId, workspaceId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(
        summary = "List workspace members",
        description = "Returns a paginated list of active employee memberships for a workspace. " +
                      "Requires workspace_members:list permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Member list returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant or workspace not found")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<WorkspaceMemberResponse>>> listMembers(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Workspace UUID") @PathVariable UUID workspaceId,
            @Parameter(description = "Page index (0-based)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("List members workspaceId={} tenantId={} by={}", workspaceId, tenantId, userDetails.getUserId());
        PageResponse<WorkspaceMemberResponse> response = memberService.listMembers(
                tenantId, workspaceId, page, size, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Transfer employee to another workspace",
        description = "Atomically removes the employee from the source workspace and adds them to the target " +
                      "workspace. The role is inherited from the source membership unless explicitly overridden. " +
                      "Source and target workspaces must be different. " +
                      "The employee must not already be a member of the target workspace. " +
                      "Requires workspace_members:create and workspace_members:delete permissions. " +
                      "Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Employee transferred — returns the new membership record",
            content = @Content(schema = @Schema(implementation = WorkspaceMemberResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error or same-workspace transfer"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant, source workspace, membership, or target workspace not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "Employee is already a member of the target workspace")
    })
    @PostMapping("/{memberId}/transfer")
    public ResponseEntity<ApiResponse<WorkspaceMemberResponse>> transferMember(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Source workspace UUID") @PathVariable UUID workspaceId,
            @Parameter(description = "Membership record UUID") @PathVariable UUID memberId,
            @Valid @RequestBody TransferWorkspaceMemberRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Transfer member memberId={} from={} to={} tenantId={} by={}",
                memberId, workspaceId, request.getTargetWorkspaceId(), tenantId, userDetails.getUserId());
        WorkspaceMemberResponse response = memberService.transferMember(
                tenantId, workspaceId, memberId, request,
                userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Remove employee from workspace",
        description = "Soft-deletes the membership record, removing the employee from the workspace. " +
                      "Requires workspace_members:delete permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204",
            description = "Member removed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant, workspace, or membership not found")
    })
    @DeleteMapping("/{memberId}")
    public ResponseEntity<Void> removeMember(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Workspace UUID") @PathVariable UUID workspaceId,
            @Parameter(description = "Membership record UUID") @PathVariable UUID memberId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Remove member memberId={} workspaceId={} tenantId={} by={}",
                memberId, workspaceId, tenantId, userDetails.getUserId());
        memberService.removeMember(tenantId, workspaceId, memberId,
                userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.noContent().build();
    }
}
