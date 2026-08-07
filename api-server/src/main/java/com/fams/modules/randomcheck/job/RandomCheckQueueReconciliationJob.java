package com.fams.modules.randomcheck.job;

import com.fams.modules.randomcheck.service.RandomCheckQueueReconciliationService;
import com.fams.shared.monitoring.ScheduledJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic (not just startup) re-run of RandomCheckQueueReconciliationService — closes a real gap
 * found via audit (2026-08-06): RandomCheckQueueReconciliationRunner only self-heals the Redis
 * dispatch queue once, at app boot. Under the actual failure mode this exists for — Redis evicting
 * keys under memory pressure (maxmemory-policy=allkeys-lru, docker-compose.yml) — the eviction
 * happens WHILE the app is running, long after the one startup pass. Without a periodic sweep, a
 * check whose queue entry got evicted mid-run had no path to ever reach 'sent': it would sit in
 * 'pending' indefinitely, invisible to NoResponseViolationJob (which only looks at status='sent'
 * rows past expiry) and invisible to the health indicators (which watch job run-time/queue lag,
 * not orphaned Postgres rows with no matching queue entry).
 *
 * Runs every 5 minutes — frequent enough that a check evicted from the queue is recovered well
 * within any reasonable random-check response window (checks default to minutes, not seconds),
 * infrequent enough to be a trivial DB+Redis cost (query is indexed on status='pending', see
 * ScheduledCheckRepository.findAllPendingForQueueReconciliation).
 */
@Slf4j
@Component
public class RandomCheckQueueReconciliationJob {

    private static final String JOB_NAME = "RandomCheckQueueReconciliationJob";

    private final RandomCheckQueueReconciliationService reconciliationService;
    private final ScheduledJobMonitor jobMonitor;

    public RandomCheckQueueReconciliationJob(RandomCheckQueueReconciliationService reconciliationService,
                                              ScheduledJobMonitor jobMonitor) {
        this.reconciliationService = reconciliationService;
        this.jobMonitor = jobMonitor;
    }

    @Scheduled(fixedRateString = "${fams.randomcheck.reconciliation.poll-rate-ms:300000}")
    public void reconcile() {
        long startedAt = System.currentTimeMillis();
        try {
            int count = reconciliationService.reconcile();
            if (count > 0) {
                log.info("RandomCheckQueueReconciliationJob — re-enqueued {} pending check(s) that were "
                        + "missing from the Redis dispatch queue", count);
            }
            jobMonitor.recordSuccess(JOB_NAME, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("RandomCheckQueueReconciliationJob failed: {}", e.getMessage(), e);
            jobMonitor.recordFailure(JOB_NAME, System.currentTimeMillis() - startedAt, e);
        }
    }
}
