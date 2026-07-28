package com.fams.modules.checkin.controller;

import com.fams.modules.checkin.dto.request.FaceResultCallbackRequest;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.service.FaceIdService;
import com.fams.modules.randomcheck.service.CheckResponseService;
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
    private final ViolationRepository violationRepository;

    public FaceResultCallbackController(
            @Value("${app.ai.internal-secret}") String internalSecret,
            CheckinRepository checkinRepository,
            CheckResponseService checkResponseService,
            FaceIdService faceIdService,
            SiteRepository siteRepository,
            ViolationRepository violationRepository) {
        this.internalSecret = internalSecret;
        this.checkinRepository = checkinRepository;
        this.checkResponseService = checkResponseService;
        this.faceIdService = faceIdService;
        this.siteRepository = siteRepository;
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

        if ("checkin".equals(request.getSourceType())) {
            checkinRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                            request.getSourceId(), request.getTenantId())
                    .ifPresent(record -> {
                        record.setFaceVerified(request.getFaceVerified());
                        record.setLivenessVerified(request.getLivenessVerified());
                        record.setFaceVerifyScore(request.getFaceVerifyScore());

                        // Mirrors the random-check module's face_fail/liveness_fail handling
                        // (CheckResponseService.applyFaceResult) — a failed/errored face check
                        // is only escalated into a violation + pending_review when the SITE
                        // actually requires Face ID. An employee who optionally submitted a
                        // photo at a non-required site just gets the informational score above,
                        // with no consequence — face verification there was best-effort, not policy.
                        boolean failed = Boolean.FALSE.equals(request.getFaceVerified());
                        if (failed) {
                            Site site = siteRepository.findById(record.getSiteId()).orElse(null);
                            if (site != null && site.isRequireFaceIdCheckin()) {
                                if ("valid".equals(record.getStatus())) {
                                    record.setStatus("pending_review");
                                }
                                String violationType = Boolean.FALSE.equals(request.getLivenessVerified())
                                        ? "liveness_fail" : "face_fail";
                                Violation violation = Violation.builder()
                                        .tenantId(record.getTenantId())
                                        .employeeId(record.getEmployeeId())
                                        .siteId(record.getSiteId())
                                        .checkinId(record.getId())
                                        .violationType(violationType)
                                        .checkDate(record.getCheckInAt().toLocalDate())
                                        .description("Face verification failed during check-in at a "
                                                + "Face-ID-required site (errorCode=" + request.getErrorCode() + ")")
                                        .resolved(false)
                                        .build();
                                violationRepository.save(violation);
                                log.info("{} violation created from checkin: checkinId={} employeeId={}",
                                        violationType, record.getId(), record.getEmployeeId());
                            }
                        }

                        checkinRepository.save(record);
                        log.info("Face result recorded: checkinId={} faceVerified={} score={} error={}",
                                record.getId(), request.getFaceVerified(),
                                request.getFaceVerifyScore(), request.getErrorCode());
                    });
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
}
