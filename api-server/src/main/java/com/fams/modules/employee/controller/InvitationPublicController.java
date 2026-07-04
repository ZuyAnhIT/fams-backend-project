package com.fams.modules.employee.controller;

import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.employee.dto.request.AcceptInvitationRequest;
import com.fams.modules.employee.dto.response.ValidateInvitationResponse;
import com.fams.modules.employee.service.EmployeeInvitationService;
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
@Tag(name = "Employee Invitations", description = "Send and manage employee email invitations")
@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationPublicController {

    private final EmployeeInvitationService invitationService;

    public InvitationPublicController(EmployeeInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @Operation(
        summary = "Validate an invitation token",
        description = "Checks whether the token is valid and pending, and returns the invited email, " +
                      "whether that email already has an account, and the tenant's display name. " +
                      "No authentication required. Frontend should use this before showing the accept form."
    )
    @SecurityRequirements
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Token is valid",
            content = @Content(schema = @Schema(implementation = ValidateInvitationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Token not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Invitation is no longer pending or has expired")
    })
    @GetMapping("/validate")
    public ResponseEntity<ApiResponse<ValidateInvitationResponse>> validateInvitation(
            @Parameter(description = "Invitation token UUID") @RequestParam UUID token) {
        log.info("Validate invitation token={}", token);
        ValidateInvitationResponse response = invitationService.validateInvitation(token);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Accept an employee invitation",
        description = "Validates the invitation token, creates an account (or links an existing one), " +
                      "assigns the tenant role, and returns JWT tokens. No authentication required — " +
                      "the invitation token acts as the credential. " +
                      "Password is required only when the invited email has no existing account."
    )
    @SecurityRequirements
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Invitation accepted; JWT tokens returned",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error or password missing for new account"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Invitation token not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "User is already a member of the tenant"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "Invitation is expired, already accepted, or cancelled")
    })
    @PostMapping("/accept")
    public ResponseEntity<ApiResponse<LoginResponse>> acceptInvitation(
            @Valid @RequestBody AcceptInvitationRequest request) {
        log.info("Accept invitation token={}", request.getToken());
        LoginResponse response = invitationService.acceptInvitation(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
