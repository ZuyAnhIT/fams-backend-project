package com.fams.modules.randomcheck.job;

import com.fams.modules.randomcheck.service.NoResponseViolationService;
import com.fams.shared.monitoring.ScheduledJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scans for sent checks whose expires_at has passed and creates no_response violations.
 * Runs every 2 minutes by default (configurable via fams.randomcheck.noresponse.poll-rate-ms).
 */
@Slf4j
@Component
public class NoResponseViolationJob {

    private static final String JOB_NAME = "NoResponseViolationJob";

    private final NoResponseViolationService noResponseViolationService;
    private final ScheduledJobMonitor jobMonitor;

    public NoResponseViolationJob(NoResponseViolationService noResponseViolationService,
                                  ScheduledJobMonitor jobMonitor) {
        this.noResponseViolationService = noResponseViolationService;
        this.jobMonitor = jobMonitor;
    }

    @Scheduled(fixedRateString = "${fams.randomcheck.noresponse.poll-rate-ms:120000}")
    public void processExpiredChecks() {
        long startedAt = System.currentTimeMillis();
        try {
            int count = noResponseViolationService.processAllExpired();
            if (count > 0) {
                log.info("NoResponseViolationJob — created {} no_response violation(s)", count);
            }
            jobMonitor.recordSuccess(JOB_NAME, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("NoResponseViolationJob failed: {}", e.getMessage(), e);
            jobMonitor.recordFailure(JOB_NAME, System.currentTimeMillis() - startedAt, e);
        }
    }
}
