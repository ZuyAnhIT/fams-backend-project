package com.fams.modules.violation.controller;

import com.fams.modules.violation.service.ViolationService;
import com.fams.shared.dto.ExplanationResponse;
import com.fams.shared.dto.SubmitExplanationRequest;
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
@Tag(name = "Violations", description = "Violation records and employee explanation")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/violations")
public class ViolationController {

    private final ViolationService violationService;

    public ViolationController(ViolationService violationService) {
        this.violationService = violationService;
    }

    @Operation(
        summary = "Employee submits explanation for a violation",
        description = "Allows the authenticated employee to attach a written explanation and optional photo URL " +
                      "to a violation record that belongs to them. " +
                      "Used to provide context to HR before the violation is resolved — for example, " +
                      "explaining why a random check was missed or why GPS was outside the geofence. " +
                      "Returns 403 if the violation belongs to a different employee. " +
                      "Returns 404 if the violation does not exist in this tenant."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Explanation recorded successfully",
            content = @Content(schema = @Schema(implementation = ExplanationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error — note is required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Violation belongs to a different employee"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Violation not found or employee profile not found")
    })
    @PostMapping("/{violationId}/explain")
    public ResponseEntity<ApiResponse<ExplanationResponse>> explainViolation(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Violation UUID") @PathVariable UUID violationId,
            @Valid @RequestBody SubmitExplanationRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Employee explain violation tenantId={} violationId={} userId={}",
                tenantId, violationId, userDetails.getUserId());
        ExplanationResponse response = violationService.explainViolation(
                tenantId, violationId, request, userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
