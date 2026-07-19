package com.fams.modules.employee.controller;

import com.fams.modules.employee.dto.request.VerifyFaceRequest;
import com.fams.modules.employee.dto.response.FaceIdStatusDto;
import com.fams.modules.employee.dto.response.VerifyFaceResultResponse;
import com.fams.modules.employee.dto.response.VerifyFaceSubmitResponse;
import com.fams.modules.employee.service.FaceIdService;
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
import org.springframework.http.HttpStatus;
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

    // ── POST /verify ──────────────────────────────────────────────────────────

    @Operation(summary = "Submit standalone Face-ID verify",
        description = "Submits a face photo for standalone 1:1 verification against the employee's enrolled embedding. "
                    + "Returns 202 Accepted with a verifyRequestId. Poll GET /verify/{verifyRequestId} for the result. "
                    + "Callable by the employee themselves or a user with face_id:manage permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Verify job accepted",
            content = @Content(schema = @Schema(implementation = VerifyFaceSubmitResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Missing or invalid photoBase64"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Employee not found or no active Face ID enrollment")
    })
    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<VerifyFaceSubmitResponse>> submitVerify(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @Valid @RequestBody VerifyFaceRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Face ID verify submit tenantId={} employeeId={} requiresLiveness={} by={}",
                tenantId, employeeId, request.isRequiresLiveness(), userDetails.getUserId());
        VerifyFaceSubmitResponse response = faceIdService.submitVerify(
                tenantId, employeeId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }

    // ── GET /verify/{verifyRequestId} ─────────────────────────────────────────

    @Operation(summary = "Poll Face-ID verify result",
        description = "Returns the current result of a previously submitted verify request. "
                    + "Poll at ~1-2 s intervals. Client should timeout after ~10-15 s. "
                    + "status=pending means the AI worker has not responded yet; "
                    + "status=pass or fail means processing is complete. "
                    + "Callable by the employee themselves or a user with face_id:manage permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Result returned",
            content = @Content(schema = @Schema(implementation = VerifyFaceResultResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "verifyRequestId not found or does not belong to this employee")
    })
    @GetMapping("/verify/{verifyRequestId}")
    public ResponseEntity<ApiResponse<VerifyFaceResultResponse>> getVerifyResult(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @Parameter(description = "Verify request UUID from POST /verify") @PathVariable UUID verifyRequestId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Face ID verify poll tenantId={} employeeId={} verifyRequestId={} by={}",
                tenantId, employeeId, verifyRequestId, userDetails.getUserId());
        VerifyFaceResultResponse response = faceIdService.getVerifyResult(
                tenantId, employeeId, verifyRequestId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
