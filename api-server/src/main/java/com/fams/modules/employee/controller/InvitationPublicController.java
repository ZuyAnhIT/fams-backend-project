package com.fams.modules.employee.controller;

import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.employee.dto.request.AcceptInvitationRequest;
import com.fams.modules.employee.service.EmployeeInvitationService;
import com.fams.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
