package com.fams.modules.rbac.controller;

import com.fams.modules.rbac.dto.request.InvitePlatformStaffRequest;
import com.fams.modules.rbac.dto.response.PlatformInvitationResponse;
import com.fams.modules.rbac.service.PlatformInvitationService;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@Tag(name = "Platform Invitations", description = "Invite, list, and cancel platform-staff email invitations — Platform Admin only")
@RestController
@RequestMapping("/api/v1/platform/invitations")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PlatformInvitationController {

    private final PlatformInvitationService invitationService;

    public PlatformInvitationController(PlatformInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @Operation(
        summary = "List platform-staff invitations",
        description = "Returns a paginated list of platform-staff invitations. Supports optional filtering by "
            + "status (pending/accepted/cancelled/expired) and email search. Platform Admin only."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitation list returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<PlatformInvitationResponse>>> listInvitations(
            @Parameter(description = "Filter by status") @RequestParam(required = false) String status,
            @Parameter(description = "Search by email") @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("List platform invitations status={} email={} by userId={}", status, email, userDetails.getUserId());
        PageResponse<PlatformInvitationResponse> response = invitationService.listInvitations(status, email, page, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Invite platform staff by email",
        description = "Sends an email invitation onboarding someone onto the FAMS platform team itself "
            + "(a platform-scoped role, not membership in any customer's company). Works whether or not the "
            + "invited email already has a FAMS account — see rbac-api.md for the two paths. Platform Admin only."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Invitation sent",
            content = @Content(schema = @Schema(implementation = PlatformInvitationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "roleId not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Pending invitation already exists for this email")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<PlatformInvitationResponse>> sendInvitation(
            @Valid @RequestBody InvitePlatformStaffRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Send platform invitation email={} by userId={}", request.getEmail(), userDetails.getUserId());
        PlatformInvitationResponse response = invitationService.sendInvitation(userDetails.getUserId(), request);
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(
        summary = "Cancel a pending platform-staff invitation",
        description = "Marks a pending invitation as cancelled so the link can no longer be used. "
            + "Only invitations in 'pending' status can be cancelled. Platform Admin only."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitation cancelled",
            content = @Content(schema = @Schema(implementation = PlatformInvitationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Invitation not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Invitation is not in pending status")
    })
    @DeleteMapping("/{invitationId}")
    public ResponseEntity<ApiResponse<PlatformInvitationResponse>> cancelInvitation(
            @Parameter(description = "Invitation UUID") @PathVariable UUID invitationId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Cancel platform invitation id={} by userId={}", invitationId, userDetails.getUserId());
        PlatformInvitationResponse response = invitationService.cancelInvitation(invitationId, userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
