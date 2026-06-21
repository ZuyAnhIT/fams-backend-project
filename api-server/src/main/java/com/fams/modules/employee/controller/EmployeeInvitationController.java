package com.fams.modules.employee.controller;

import com.fams.modules.employee.dto.request.InviteEmployeeRequest;
import com.fams.modules.employee.dto.response.InvitationResponse;
import com.fams.modules.employee.service.EmployeeInvitationService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@Tag(name = "Employee Invitations", description = "Send and manage employee email invitations")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/invitations")
public class EmployeeInvitationController {

    private final EmployeeInvitationService invitationService;

    public EmployeeInvitationController(EmployeeInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @Operation(
        summary = "Send employee invitation",
        description = "Sends an email invitation to onboard an employee into the tenant. " +
                      "Requires the employees:create permission within the target tenant. " +
                      "Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Invitation sent",
            content = @Content(schema = @Schema(implementation = InvitationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Pending invitation already exists for this email in the tenant")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<InvitationResponse>> sendInvitation(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Valid @RequestBody InviteEmployeeRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Send invitation tenantId={} email={} by userId={}", tenantId, request.getEmail(), userDetails.getUserId());
        InvitationResponse response = invitationService.sendInvitation(
                tenantId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(
        summary = "Cancel a pending invitation",
        description = "Marks a pending invitation as cancelled so the link can no longer be used. " +
                      "Only invitations in 'pending' status can be cancelled. " +
                      "Requires the employees:create permission within the tenant. " +
                      "Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitation cancelled",
            content = @Content(schema = @Schema(implementation = InvitationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant or invitation not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Invitation is not in pending status")
    })
    @DeleteMapping("/{invitationId}")
    public ResponseEntity<ApiResponse<InvitationResponse>> cancelInvitation(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Invitation UUID") @PathVariable UUID invitationId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Cancel invitation id={} tenantId={} by userId={}", invitationId, tenantId, userDetails.getUserId());
        InvitationResponse response = invitationService.cancelInvitation(
                tenantId, invitationId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
