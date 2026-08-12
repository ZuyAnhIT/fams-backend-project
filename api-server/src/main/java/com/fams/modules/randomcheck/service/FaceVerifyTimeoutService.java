package com.fams.modules.randomcheck.service;

import com.fams.modules.randomcheck.entity.CheckResponse;
import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.repository.CheckResponseRepository;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.modules.violation.entity.Violation;
import com.fams.modules.violation.repository.ViolationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 2026-08-12 backend readiness assessment: closes a real gap found by tracing the random-check
 * lifecycle end-to-end. A face/liveness check_response is created with outcome='pass' and
 * faceVerified=NULL the moment the employee submits (see CheckResponseService.submit()) — the
 * real verification result arrives LATER via an async callback from fams-ai
 * (CheckResponseService.applyFaceResult(), invoked by FaceResultCallbackController). If that
 * callback never arrives (fams-ai down, crashed, network partition, or the job itself lost),
 * the response silently stayed outcome='pass' forever with no job ever revisiting it —
 * NoResponseViolationJob only scans status='sent' checks (this one is already 'responded'), and
 * RandomCheckQueueReconciliationJob only recovers checks lost from the Redis dispatch queue, not
 * responses stuck mid-verification. That meant a real face-verification failure (or an AI outage
 * at exactly the wrong moment) could be recorded as a false "pass" that fed straight into
 * AttendanceSummaryService's hasRandomCheckFailure flag with no way to ever catch it.
 *
 * Fails closed (same stance as the rest of this module — no enrolled profile / no photo also
 * fail closed rather than silently passing): past the timeout, mark it a distinct
 * 'face_verify_timeout' violation type — NOT the same as 'face_fail' — so HR can tell "AI
 * service was unavailable" apart from "employee genuinely failed verification" and dismiss it
 * accordingly if it turns out to be the former.
 */
@Slf4j
@Service
public class FaceVerifyTimeoutService {

    private final CheckResponseRepository checkResponseRepository;
    private final ScheduledCheckRepository scheduledCheckRepository;
    private final ViolationRepository violationRepository;
    private final int timeoutMinutes;

    public FaceVerifyTimeoutService(CheckResponseRepository checkResponseRepository,
                                     ScheduledCheckRepository scheduledCheckRepository,
                                     ViolationRepository violationRepository,
                                     @Value("${fams.randomcheck.face-verify-timeout-minutes:10}") int timeoutMinutes) {
        this.checkResponseRepository = checkResponseRepository;
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.violationRepository = violationRepository;
        this.timeoutMinutes = timeoutMinutes;
    }

    /** Called by the scheduled job. Returns how many responses were resolved as timed out. */
    @Transactional
    public int processStaleResponses() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(timeoutMinutes);
        List<CheckResponse> stale = checkResponseRepository
                .findByPhotoSubmittedTrueAndFaceVerifiedIsNullAndRespondedAtBefore(cutoff);

        int count = 0;
        for (CheckResponse response : stale) {
            response.setFaceVerified(false);
            response.setOutcome("fail");
            String fr = response.getFailureReason();
            response.setFailureReason(fr != null ? fr + ",face_verify_timeout" : "face_verify_timeout");
            checkResponseRepository.save(response);

            scheduledCheckRepository.findById(response.getScheduledCheckId()).ifPresent(check -> {
                createTimeoutViolation(check, response.getId());
            });

            count++;
            log.warn("Face verification timed out after {}min: checkResponseId={} scheduledCheckId={} "
                    + "employeeId={} — marked as failed, fams-ai callback never arrived",
                    timeoutMinutes, response.getId(), response.getScheduledCheckId(), response.getEmployeeId());
        }

        if (count > 0) {
            log.warn("FaceVerifyTimeoutService — resolved {} stuck face-verification response(s) as timed out",
                    count);
        }
        return count;
    }

    private void createTimeoutViolation(ScheduledCheck check, java.util.UUID checkResponseId) {
        // Idempotency guard — same pattern as CheckResponseService#createViolation.
        if (violationRepository.existsByScheduledCheckIdAndViolationType(check.getId(), "face_verify_timeout")) {
            return;
        }
        Violation violation = Violation.builder()
                .tenantId(check.getTenantId())
                .employeeId(check.getEmployeeId())
                .siteId(check.getSiteId())
                .scheduledCheckId(check.getId())
                .checkResponseId(checkResponseId)
                .violationType("face_verify_timeout")
                .checkDate(check.getCheckDate())
                .description("Face/liveness verification result never arrived within " + timeoutMinutes
                        + " minutes — likely an AI service outage, not necessarily a real verification "
                        + "failure. Review before confirming.")
                .resolved(false)
                .build();
        violationRepository.save(violation);
        log.info("face_verify_timeout violation created: checkId={} employeeId={}",
                check.getId(), check.getEmployeeId());
    }
}
