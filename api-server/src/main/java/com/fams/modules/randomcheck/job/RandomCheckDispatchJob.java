package com.fams.modules.randomcheck.job;

import com.fams.modules.randomcheck.redis.RandomCheckDispatchQueue;
import com.fams.modules.randomcheck.service.RandomCheckDispatchService;
import com.fams.shared.monitoring.ScheduledJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Polls the Redis dispatch queue every minute and dispatches checks whose
 * scheduled_at time has arrived.
 *
 * This is the Spring/Java equivalent of a Bull/BullMQ delayed-job processor.
 */
@Slf4j
@Component
public class RandomCheckDispatchJob {

    private static final String JOB_NAME = "RandomCheckDispatchJob";

    private final RandomCheckDispatchQueue dispatchQueue;
    private final RandomCheckDispatchService dispatchService;
    private final ScheduledJobMonitor jobMonitor;

    public RandomCheckDispatchJob(RandomCheckDispatchQueue dispatchQueue,
                                  RandomCheckDispatchService dispatchService,
                                  ScheduledJobMonitor jobMonitor) {
        this.dispatchQueue = dispatchQueue;
        this.dispatchService = dispatchService;
        this.jobMonitor = jobMonitor;
    }

    @Scheduled(fixedRateString = "${fams.randomcheck.dispatch.poll-rate-ms:60000}")
    public void dispatchDueChecks() {
        long startedAt = System.currentTimeMillis();
        try {
            List<UUID> due = dispatchQueue.dequeueExpired();
            if (due.isEmpty()) {
                jobMonitor.recordSuccess(JOB_NAME, System.currentTimeMillis() - startedAt);
                return;
            }

            log.info("RandomCheckDispatchJob — dispatching {} due check(s)", due.size());
            for (UUID checkId : due) {
                try {
                    dispatchService.dispatch(checkId);
                } catch (Exception e) {
                    log.error("Failed to dispatch checkId={}: {}", checkId, e.getMessage(), e);
                }
            }
            jobMonitor.recordSuccess(JOB_NAME, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("RandomCheckDispatchJob failed: {}", e.getMessage(), e);
            jobMonitor.recordFailure(JOB_NAME, System.currentTimeMillis() - startedAt, e);
        }
    }
}
