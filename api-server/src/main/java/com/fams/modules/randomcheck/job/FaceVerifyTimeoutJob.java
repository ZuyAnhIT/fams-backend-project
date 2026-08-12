package com.fams.modules.randomcheck.job;

import com.fams.modules.randomcheck.service.FaceVerifyTimeoutService;
import com.fams.shared.monitoring.ScheduledJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 2026-08-12 backend readiness assessment: reconciliation job for check_response rows stuck
 * waiting on an async face/liveness verification callback from fams-ai that never arrived — see
 * FaceVerifyTimeoutService for the full explanation of the gap this closes. Runs every 2 minutes
 * by default, same cadence as NoResponseViolationJob (a sibling reconciliation job).
 */
@Slf4j
@Component
public class FaceVerifyTimeoutJob {

    private static final String JOB_NAME = "FaceVerifyTimeoutJob";

    private final FaceVerifyTimeoutService faceVerifyTimeoutService;
    private final ScheduledJobMonitor jobMonitor;

    public FaceVerifyTimeoutJob(FaceVerifyTimeoutService faceVerifyTimeoutService,
                                 ScheduledJobMonitor jobMonitor) {
        this.faceVerifyTimeoutService = faceVerifyTimeoutService;
        this.jobMonitor = jobMonitor;
    }

    @Scheduled(fixedRateString = "${fams.randomcheck.face-verify-timeout.poll-rate-ms:120000}")
    public void processStaleResponses() {
        long startedAt = System.currentTimeMillis();
        try {
            int count = faceVerifyTimeoutService.processStaleResponses();
            if (count > 0) {
                log.info("FaceVerifyTimeoutJob — resolved {} stuck face-verification response(s)", count);
            }
            jobMonitor.recordSuccess(JOB_NAME, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("FaceVerifyTimeoutJob failed: {}", e.getMessage(), e);
            jobMonitor.recordFailure(JOB_NAME, System.currentTimeMillis() - startedAt, e);
        }
    }
}
