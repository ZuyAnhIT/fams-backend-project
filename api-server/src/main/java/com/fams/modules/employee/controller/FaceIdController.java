package com.fams.modules.employee.controller;

import com.fams.modules.employee.dto.request.RejectFaceEnrollmentRequest;
import com.fams.modules.employee.dto.request.VerifyFaceRequest;
import com.fams.modules.employee.dto.response.FaceIdStatusDto;
import com.fams.modules.employee.dto.response.LivenessChallengeResponse;
import com.fams.modules.employee.dto.response.LivenessChallengeResultResponse;
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
                    + "ONLY callable by the employee themselves (matching this authenticated user's userId) — "
                    + "biometric consent must come from the data subject, not from HR/Admin on their behalf, "
                    + "even with face_id:manage. face_id:manage is enough for enroll/revoke, not for this endpoint.")
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

    @Operation(summary = "Submit Face ID enrollment for review",
        description = "Uploads 3-5 photos of the SAME person, each run through anti-spoofing (liveness) and "
                    + "face-detection, then cross-checked against each other to confirm they're the same person. "
                    + "Does NOT activate the face immediately — lands in a pending state for HR/Admin to approve "
                    + "via POST /approve. If the employee already has an approved enrollment, it keeps working for "
                    + "checkin while this new submission is under review. Consent must be given first via POST /consent. "
                    + "Callable by the employee themselves or a user with face_id:manage permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Submitted, awaiting HR review",
            content = @Content(schema = @Schema(implementation = FaceIdStatusDto.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Wrong photo count, no face detected, multiple faces, failed anti-spoofing, or photos don't match each other"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions or consent not given"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Employee not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "A previous submission is still pending review")
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

    // ── POST /liveness-challenge ─────────────────────────────────────────────

    @Operation(summary = "Start an active-liveness challenge",
        description = "Returns a random ordered sequence of actions (always 'center' first, then 2 random "
                    + "dynamic actions from turn_left/turn_right/look_up/look_down/blink) the caller must "
                    + "perform, one photo per action, submitted in order to POST .../liveness-challenge/{challengeId}/frames "
                    + "within the challenge's expiry window. Required for self-service enrollment (see POST /enroll/from-challenge) "
                    + "and for check-in at a site with requireFaceIdCheckin=true. "
                    + "Callable by the employee themselves or a user with face_id:manage permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Challenge started",
            content = @Content(schema = @Schema(implementation = LivenessChallengeResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid purpose"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Employee not found")
    })
    @PostMapping("/liveness-challenge")
    public ResponseEntity<ApiResponse<LivenessChallengeResponse>> startLivenessChallenge(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @Parameter(description = "'enroll' or 'checkin'") @RequestParam String purpose,
            @Parameter(description = "Site UUID — REQUIRED when purpose=checkin (binds the challenge to that "
                    + "site so it can't be consumed by a check-in at a different one); ignored for purpose=enroll")
            @RequestParam(required = false) UUID siteId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Liveness challenge start tenantId={} employeeId={} purpose={} siteId={} by={}",
                tenantId, employeeId, purpose, siteId, userDetails.getUserId());
        LivenessChallengeResponse response = faceIdService.startLivenessChallenge(
                tenantId, employeeId, purpose, siteId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ── POST /liveness-challenge/{challengeId}/frames ────────────────────────

    @Operation(summary = "Submit frames for an active-liveness challenge",
        description = "Uploads exactly one photo per action returned by POST /liveness-challenge, in the same "
                    + "order. Verified via head-pose estimation (turn/look actions) and eye-aspect-ratio (blink), "
                    + "plus same-person cross-check across all frames and anti-spoofing on the 'center' frame. "
                    + "On success (status=passed), the verified result is held server-side for the next step "
                    + "(POST /enroll/from-challenge or POST .../checkin with livenessChallengeId) — nothing further "
                    + "to upload.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Challenge evaluated — check response body's status (passed/failed), this endpoint does not itself error on a failed challenge",
            content = @Content(schema = @Schema(implementation = LivenessChallengeResultResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Wrong number of frames"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Challenge not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Challenge already completed or expired")
    })
    @PostMapping(value = "/liveness-challenge/{challengeId}/frames", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<LivenessChallengeResultResponse>> submitLivenessChallengeFrames(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @Parameter(description = "Challenge UUID from POST /liveness-challenge") @PathVariable UUID challengeId,
            @Parameter(description = "One photo per action, same order as the challenge's actions list")
            @RequestPart("frames") List<MultipartFile> frames,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Liveness challenge frames tenantId={} employeeId={} challengeId={} frames={} by={}",
                tenantId, employeeId, challengeId, frames.size(), userDetails.getUserId());
        LivenessChallengeResultResponse response = faceIdService.submitLivenessChallengeFrames(
                tenantId, employeeId, challengeId, frames, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ── POST /enroll/from-challenge ───────────────────────────────────────────

    @Operation(summary = "Submit Face ID enrollment from a passed active-liveness challenge (self-service path)",
        description = "The self-service equivalent of POST /enroll — instead of uploading raw photos, references "
                    + "a challenge (purpose=enroll) that already passed via POST .../liveness-challenge/{challengeId}/frames. "
                    + "Same pending-review outcome as POST /enroll. Required (not just recommended) when the caller "
                    + "is enrolling their own face — POST /enroll rejects self-service calls.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Submitted, awaiting HR review",
            content = @Content(schema = @Schema(implementation = FaceIdStatusDto.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions or consent not given"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Employee not found, or challenge not found/not passed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "A previous submission is still pending review")
    })
    @PostMapping("/enroll/from-challenge")
    public ResponseEntity<ApiResponse<FaceIdStatusDto>> enrollFromChallenge(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @Parameter(description = "A PASSED, purpose=enroll challenge UUID") @RequestParam UUID challengeId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Face ID enroll-from-challenge tenantId={} employeeId={} challengeId={} by={}",
                tenantId, employeeId, challengeId, userDetails.getUserId());
        FaceIdStatusDto response = faceIdService.enrollFaceFromChallenge(
                tenantId, employeeId, challengeId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ── POST /approve ─────────────────────────────────────────────────────────

    @Operation(summary = "Approve a pending Face ID enrollment",
        description = "Promotes the employee's pending submission to the live, checkin-usable face. "
                    + "HR/Admin only — cannot be called by the employee on their own submission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Approved",
            content = @Content(schema = @Schema(implementation = FaceIdStatusDto.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No pending submission for this employee")
    })
    @PostMapping("/approve")
    public ResponseEntity<ApiResponse<FaceIdStatusDto>> approve(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Face ID approve tenantId={} employeeId={} by={}", tenantId, employeeId, userDetails.getUserId());
        FaceIdStatusDto response = faceIdService.approveEnrollment(
                tenantId, employeeId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ── POST /reject ──────────────────────────────────────────────────────────

    @Operation(summary = "Reject a pending Face ID enrollment",
        description = "Discards the employee's pending submission with a reason. If this was a re-enrollment, "
                    + "the previously-approved face (if any) is untouched and keeps working for checkin.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Rejected",
            content = @Content(schema = @Schema(implementation = FaceIdStatusDto.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing reason"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No pending submission for this employee")
    })
    @PostMapping("/reject")
    public ResponseEntity<ApiResponse<FaceIdStatusDto>> reject(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @Valid @RequestBody RejectFaceEnrollmentRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Face ID reject tenantId={} employeeId={} by={}", tenantId, employeeId, userDetails.getUserId());
        FaceIdStatusDto response = faceIdService.rejectEnrollment(
                tenantId, employeeId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ── GET /pending-review/photo ─────────────────────────────────────────────

    @Operation(summary = "Reference photo for a pending Face ID submission",
        description = "The representative frame HR should look at before approving/rejecting — without this, "
                    + "HR was approving based on metadata alone (name, photo count, timestamp), never actually "
                    + "seeing a face. Requires face_id:manage and (for site-scoped roles) the employee to fall "
                    + "within the caller's allowed sites.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "JPEG image bytes"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions or outside site-scope"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Employee not found or no pending submission")
    })
    @GetMapping(value = "/pending-review/photo", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> getPendingReviewPhoto(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Face ID pending review photo tenantId={} employeeId={} by={}",
                tenantId, employeeId, userDetails.getUserId());
        byte[] photo = faceIdService.getPendingReviewPhoto(
                tenantId, employeeId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(photo);
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
