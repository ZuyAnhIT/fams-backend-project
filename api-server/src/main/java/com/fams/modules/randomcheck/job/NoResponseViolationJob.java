package com.fams.modules.randomcheck.job;

import com.fams.modules.randomcheck.service.NoResponseViolationService;
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

    private final NoResponseViolationService noResponseViolationService;

    public NoResponseViolationJob(NoResponseViolationService noResponseViolationService) {
        this.noResponseViolationService = noResponseViolationService;
    }

    @Scheduled(fixedRateString = "${fams.randomcheck.noresponse.poll-rate-ms:120000}")
    public void processExpiredChecks() {
        int count = noResponseViolationService.processAllExpired();
        if (count > 0) {
            log.info("NoResponseViolationJob — created {} no_response violation(s)", count);
        }
    }
}
