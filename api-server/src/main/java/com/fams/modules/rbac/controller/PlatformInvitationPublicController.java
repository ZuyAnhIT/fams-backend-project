package com.fams.modules.rbac.controller;

import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.rbac.dto.request.AcceptPlatformInvitationRequest;
import com.fams.modules.rbac.dto.response.ValidatePlatformInvitationResponse;
import com.fams.modules.rbac.service.PlatformInvitationService;
import com.fams.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@Tag(name = "Platform Invitations", description = "Public endpoints to validate and accept a platform-staff invitation")
@RestController
@RequestMapping("/api/v1/platform-invitations")
public class PlatformInvitationPublicController {

    private final PlatformInvitationService invitationService;

    public PlatformInvitationPublicController(PlatformInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @Operation(
        summary = "Validate a platform-staff invitation token",
        description = "Checks whether the token is valid and pending, and returns the invited email and whether "
            + "that email already has an account. No authentication required."
    )
    @SecurityRequirements
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Token is valid",
            content = @Content(schema = @Schema(implementation = ValidatePlatformInvitationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Token not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Invitation is no longer pending or has expired")
    })
    @GetMapping("/validate")
    public ResponseEntity<ApiResponse<ValidatePlatformInvitationResponse>> validateInvitation(
            @Parameter(description = "Invitation token UUID") @RequestParam UUID token) {
        log.info("Validate platform invitation token={}", token);
        ValidatePlatformInvitationResponse response = invitationService.validateInvitation(token);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Accept a platform-staff invitation",
        description = "Validates the invitation token, creates an account (or reuses an existing one), assigns "
            + "the platform-scoped role, and returns JWT tokens. No authentication required — the invitation "
            + "token acts as the credential. Password is required only when the invited email has no existing account."
    )
    @SecurityRequirements
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitation accepted; JWT tokens returned",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error or password missing for new account"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Invitation token not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "User already holds this platform role"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "Invitation is expired, already accepted, or cancelled")
    })
    @PostMapping("/accept")
    public ResponseEntity<ApiResponse<LoginResponse>> acceptInvitation(
            @Valid @RequestBody AcceptPlatformInvitationRequest request) {
        log.info("Accept platform invitation token={}", request.getToken());
        LoginResponse response = invitationService.acceptInvitation(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
