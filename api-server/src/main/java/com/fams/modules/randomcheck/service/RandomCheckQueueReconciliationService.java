package com.fams.modules.randomcheck.service;

import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.redis.RandomCheckDispatchQueue;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Re-populates the Redis dispatch queue (fams:randomcheck:dispatch) from the scheduled_checks
 * table's 'pending' rows — the queue has no other source of truth, so any check whose queue entry
 * is lost (Redis eviction under memory pressure, or a crash between the row being created and the
 * ZADD) needs this to ever reach 'sent'.
 *
 * Extracted (audit 2026-08-06) from RandomCheckQueueReconciliationRunner, which only ran once at
 * app startup — meaning a Redis eviction happening WHILE the app stays up (the actual common case
 * for allkeys-lru under memory pressure, not just restarts) left affected checks stuck in
 * 'pending' forever with zero self-healing until the next deploy/restart. Now used by both the
 * startup runner (immediate recovery on boot) and RandomCheckQueueReconciliationJob (periodic
 * recovery while running, see that class for the schedule).
 *
 * Idempotent and safe to re-run: ZADD on an already-present member just updates its score (Redis
 * sorted-set semantics), so re-enqueueing a check that's already queued is a no-op in effect.
 */
@Service
public class RandomCheckQueueReconciliationService {

    private final ScheduledCheckRepository scheduledCheckRepository;
    private final RandomCheckDispatchQueue dispatchQueue;

    public RandomCheckQueueReconciliationService(ScheduledCheckRepository scheduledCheckRepository,
                                                  RandomCheckDispatchQueue dispatchQueue) {
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.dispatchQueue = dispatchQueue;
    }

    /** @return number of checks re-enqueued */
    public int reconcile() {
        List<ScheduledCheck> pending = scheduledCheckRepository.findAllPendingForQueueReconciliation();
        for (ScheduledCheck check : pending) {
            dispatchQueue.enqueue(check.getId(), check.getScheduledAt());
        }
        return pending.size();
    }
}
