package com.fams.modules.checkin.controller;

import com.fams.modules.checkin.dto.request.FaceResultCallbackRequest;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.service.FaceIdService;
import com.fams.modules.randomcheck.service.CheckResponseService;
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.violation.entity.Violation;
import com.fams.modules.violation.repository.ViolationRepository;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Hidden
@Slf4j
@RestController
@RequestMapping("/internal/ai-callback")
public class FaceResultCallbackController {

    private static final String HEADER_SECRET = "X-Internal-Secret";

    private final String internalSecret;
    private final CheckinRepository checkinRepository;
    private final CheckResponseService checkResponseService;
    private final FaceIdService faceIdService;
    private final SiteRepository siteRepository;
    private final ShiftRepository shiftRepository;
    private final ViolationRepository violationRepository;

    public FaceResultCallbackController(
            @Value("${app.ai.internal-secret}") String internalSecret,
            CheckinRepository checkinRepository,
            CheckResponseService checkResponseService,
            FaceIdService faceIdService,
            SiteRepository siteRepository,
            ShiftRepository shiftRepository,
            ViolationRepository violationRepository) {
        this.internalSecret = internalSecret;
        this.checkinRepository = checkinRepository;
        this.checkResponseService = checkResponseService;
        this.faceIdService = faceIdService;
        this.siteRepository = siteRepository;
        this.shiftRepository = shiftRepository;
        this.violationRepository = violationRepository;
    }

    @PostMapping("/face-result")
    @Transactional
    public ResponseEntity<Void> handleFaceResult(
            @RequestHeader(value = HEADER_SECRET, required = false) String secret,
            @RequestBody FaceResultCallbackRequest request) {
        if (!internalSecret.equals(secret)) {
            log.warn("Face result callback rejected: invalid secret sourceId={}", request.getSourceId());
            return ResponseEntity.status(403).build();
        }

        if ("checkin".equals(request.getSourceType()) || "checkout".equals(request.getSourceType())) {
            applyCheckinOrCheckoutFaceResult(request, "checkout".equals(request.getSourceType()));
        } else if ("check_response".equals(request.getSourceType())) {
            checkResponseService.applyFaceResult(
                    request.getSourceId(),
                    request.getFaceVerified(),
                    request.getLivenessVerified(),
                    request.getFaceVerifyScore());
        } else if ("standalone_verify".equals(request.getSourceType())) {
            faceIdService.applyVerifyResult(
                    request.getSourceId(),
                    request.getTenantId(),
                    request.getFaceVerified(),
                    request.getLivenessVerified(),
                    request.getFaceVerifyScore(),
                    request.getErrorCode());
        } else {
            log.warn("Unhandled callback sourceType={} sourceId={}",
                    request.getSourceType(), request.getSourceId());
        }

        return ResponseEntity.ok().build();
    }

    /** Shared by both check-in and check-out results — same escalation/violation treatment,
     *  just routed to separate columns (face_verified/... vs checkout_face_verified/...) since a
     *  single CheckinRecord row carries both a check-in AND (once it happens) a check-out result. */
    private void applyCheckinOrCheckoutFaceResult(FaceResultCallbackRequest request, boolean isCheckout) {
        checkinRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                        request.getSourceId(), request.getTenantId())
                .ifPresent(record -> {
                    if (isCheckout) {
                        checkinRepository.applyCheckoutFaceResult(record.getId(), request.getFaceVerified(),
                                request.getLivenessVerified(), request.getFaceVerifyScore());
                    } else {
                        checkinRepository.applyCheckinFaceResult(record.getId(), request.getFaceVerified(),
                                request.getLivenessVerified(), request.getFaceVerifyScore());
                    }

                    // Mirrors the random-check module's face_fail/liveness_fail handling
                    // (CheckResponseService.applyFaceResult) — a failed/errored face check is
                    // only escalated into a violation + pending_review when the effective policy
                    // (site, with the shift's optional override) actually required Face ID/
                    // liveness. An employee who optionally submitted a photo under a gps_only
                    // policy just gets the informational score above, no consequence — that
                    // verification was best-effort, not policy.
                    boolean failed = Boolean.FALSE.equals(request.getFaceVerified());
                    if (failed) {
                        Site site = siteRepository.findById(record.getSiteId()).orElse(null);
                        Shift shift = (site != null && record.getShiftId() != null)
                                ? shiftRepository.findById(record.getShiftId()).orElse(null)
                                : null;
                        String policy = site != null
                                ? (shift != null && shift.getCheckinPolicyOverride() != null
                                        ? shift.getCheckinPolicyOverride() : site.getCheckinPolicy())
                                : "gps_only";
                        boolean faceRequired = "gps_face".equals(policy) || "gps_face_liveness".equals(policy);

                        if (faceRequired) {
                            checkinRepository.escalateToPendingReviewIfValid(record.getId());
                            String violationType = Boolean.FALSE.equals(request.getLivenessVerified())
                                    ? "liveness_fail" : "face_fail";
                            Violation violation = Violation.builder()
                                    .tenantId(record.getTenantId())
                                    .employeeId(record.getEmployeeId())
                                    .siteId(record.getSiteId())
                                    .checkinId(record.getId())
                                    .violationType(violationType)
                                    .checkDate(record.getCheckInAt().toLocalDate())
                                    .description("Face verification failed during "
                                            + (isCheckout ? "check-out" : "check-in")
                                            + " at a site/shift requiring it (errorCode=" + request.getErrorCode() + ")")
                                    .resolved(false)
                                    .build();
                            violationRepository.save(violation);
                            log.info("{} violation created from {}: checkinId={} employeeId={}",
                                    violationType, isCheckout ? "checkout" : "checkin",
                                    record.getId(), record.getEmployeeId());
                        }
                    }

                    log.info("Face result recorded ({}): checkinId={} faceVerified={} score={} error={}",
                            isCheckout ? "checkout" : "checkin", record.getId(), request.getFaceVerified(),
                            request.getFaceVerifyScore(), request.getErrorCode());
                });
    }
}
