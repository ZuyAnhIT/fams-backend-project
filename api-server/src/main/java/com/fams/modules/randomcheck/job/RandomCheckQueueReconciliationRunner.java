package com.fams.modules.randomcheck.job;

import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.redis.RandomCheckDispatchQueue;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Re-populates the Redis dispatch queue (fams:randomcheck:dispatch) from the scheduled_checks
 * table once on every application startup.
 *
 * The queue (see RandomCheckDispatchQueue) has no other source of truth — entries are written
 * once at generation time and never reconstructed. Found via audit (2026-08-01): an app restart
 * (deploy, crash-recovery) or a Redis key eviction under memory pressure
 * (docker-compose.yml configures maxmemory-policy=allkeys-lru) silently drops already-generated
 * 'pending' checks out of the queue with no self-healing — they'd never get their "sent"
 * dispatch/notification step, just eventually time out as no_response and raise a violation for
 * an employee who was, from their point of view, never actually notified.
 *
 * Idempotent and safe to re-run: ZADD on an already-present member just updates its score
 * (Redis sorted-set semantics), so re-enqueueing a check that's already queued is a no-op in
 * effect. Only 'pending' checks are re-enqueued — 'sent' ones have already had their dispatch
 * step run (re-queuing them would risk a duplicate notification for a narrower, rarer edge case:
 * a crash between marking status='sent' and the notification actually going out — not addressed
 * here, see docs/api/random-check-config-review.md for the tracked list of remaining P2s).
 */
@Slf4j
@Component
public class RandomCheckQueueReconciliationRunner implements ApplicationRunner {

    private final ScheduledCheckRepository scheduledCheckRepository;
    private final RandomCheckDispatchQueue dispatchQueue;

    public RandomCheckQueueReconciliationRunner(ScheduledCheckRepository scheduledCheckRepository,
                                                RandomCheckDispatchQueue dispatchQueue) {
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.dispatchQueue = dispatchQueue;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<ScheduledCheck> pending = scheduledCheckRepository.findAllPendingForQueueReconciliation();
            for (ScheduledCheck check : pending) {
                dispatchQueue.enqueue(check.getId(), check.getScheduledAt());
            }
            log.info("RandomCheckQueueReconciliationRunner — re-enqueued {} pending check(s) into the "
                    + "Redis dispatch queue on startup", pending.size());
        } catch (Exception e) {
            log.error("RandomCheckQueueReconciliationRunner failed — dispatch queue may be missing "
                    + "entries until the next restart: {}", e.getMessage(), e);
        }
    }
}
