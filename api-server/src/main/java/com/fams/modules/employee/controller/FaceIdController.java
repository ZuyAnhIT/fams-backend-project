package com.fams.modules.employee.controller;

import com.fams.modules.employee.dto.response.FaceIdStatusDto;
import com.fams.modules.employee.service.FaceIdService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "Face ID", description = "Face ID enrollment and consent management")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/employees/{employeeId}/face-id")
public class FaceIdController {

    private final FaceIdService faceIdService;

    public FaceIdController(FaceIdService faceIdService) {
        this.faceIdService = faceIdService;
    }

    // ── POST /consent ─────────────────────────────────────────────────────────

    @Operation(summary = "Record Face ID consent",
        description = "Records the employee's consent to collect and process facial biometric data. "
                    + "Must be called before enrollment. Idempotent. "
                    + "Callable by the employee themselves or a user with face_id:manage permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Consent recorded",
            content = @Content(schema = @Schema(implementation = FaceIdStatusDto.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Employee not found")
    })
    @PostMapping("/consent")
    public ResponseEntity<ApiResponse<FaceIdStatusDto>> giveConsent(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Face ID consent tenantId={} employeeId={} by={}", tenantId, employeeId, userDetails.getUserId());
        FaceIdStatusDto response = faceIdService.giveConsent(
                tenantId, employeeId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ── GET / (status) ────────────────────────────────────────────────────────

    @Operation(summary = "Get Face ID status",
        description = "Returns the current Face ID enrollment status for the employee. "
                    + "Callable by the employee themselves or a user with employees:read or face_id:manage permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status returned",
            content = @Content(schema = @Schema(implementation = FaceIdStatusDto.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Employee not found")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<FaceIdStatusDto>> getStatus(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Face ID status tenantId={} employeeId={} by={}", tenantId, employeeId, userDetails.getUserId());
        FaceIdStatusDto response = faceIdService.getStatus(
                tenantId, employeeId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ── DELETE / (revoke) ─────────────────────────────────────────────────────

    @Operation(summary = "Revoke Face ID",
        description = "Revokes the employee's Face ID enrollment. Deletes stored photos and clears the embedding. "
                    + "The employee must re-consent and re-enroll to use Face ID again. "
                    + "Callable by the employee themselves or a user with face_id:manage permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Face ID revoked",
            content = @Content(schema = @Schema(implementation = FaceIdStatusDto.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Employee or Face ID profile not found")
    })
    @DeleteMapping
    public ResponseEntity<ApiResponse<FaceIdStatusDto>> revoke(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Face ID revoke tenantId={} employeeId={} by={}", tenantId, employeeId, userDetails.getUserId());
        FaceIdStatusDto response = faceIdService.revokeFace(
                tenantId, employeeId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ── POST /enroll ──────────────────────────────────────────────────────────

    @Operation(summary = "Enroll Face ID",
        description = "Uploads 3-5 photos to enroll the employee's face. Consent must be given first via POST /consent. "
                    + "Each photo must contain exactly one face. "
                    + "Callable by the employee themselves or a user with face_id:manage permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Enrollment complete",
            content = @Content(schema = @Schema(implementation = FaceIdStatusDto.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Wrong photo count, no face detected, or multiple faces detected"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions or consent not given"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Employee not found")
    })
    @PostMapping(value = "/enroll", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<FaceIdStatusDto>> enroll(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @Parameter(description = "3-5 face photos (JPEG/PNG)") @RequestPart("photos") List<MultipartFile> photos,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Face ID enroll tenantId={} employeeId={} photos={} by={}",
                tenantId, employeeId, photos.size(), userDetails.getUserId());
        FaceIdStatusDto response = faceIdService.enrollFace(
                tenantId, employeeId, photos, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
