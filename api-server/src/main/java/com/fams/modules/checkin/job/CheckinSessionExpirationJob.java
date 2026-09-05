package com.fams.modules.checkin.job;

import com.fams.modules.checkin.service.CheckinService;
import com.fams.shared.monitoring.ScheduledJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/** Proactively closes forgotten checkouts. API reads remain a second safety net, but the
 *  employee no longer has to reopen or refresh the app for an expired session to stop being
 *  considered active by dashboards and uniqueness constraints. */
@Slf4j
@Component
public class CheckinSessionExpirationJob {

    public static final String JOB_NAME = "CheckinSessionExpirationJob";

    private final CheckinService checkinService;
    private final ScheduledJobMonitor jobMonitor;

    public CheckinSessionExpirationJob(CheckinService checkinService,
                                       ScheduledJobMonitor jobMonitor) {
        this.checkinService = checkinService;
        this.jobMonitor = jobMonitor;
    }

    @Scheduled(fixedDelayString = "${fams.checkin.session-expiration-delay-ms:30000}",
            initialDelayString = "${fams.checkin.session-expiration-initial-delay-ms:5000}")
    public void closeExpiredSessions() {
        long started = System.currentTimeMillis();
        try {
            int closed = checkinService.expireDueOpenSessions(OffsetDateTime.now());
            if (closed > 0) {
                log.info("Closed {} expired check-in session(s)", closed);
            }
            jobMonitor.recordSuccess(JOB_NAME, System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.error("Failed to close expired check-in sessions", e);
            jobMonitor.recordFailure(JOB_NAME, System.currentTimeMillis() - started, e);
        }
    }
}
