package com.fams.modules.randomcheck.job;

import com.fams.modules.randomcheck.redis.RandomCheckDispatchQueue;
import com.fams.modules.randomcheck.service.RandomCheckDispatchService;
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

    private final RandomCheckDispatchQueue dispatchQueue;
    private final RandomCheckDispatchService dispatchService;

    public RandomCheckDispatchJob(RandomCheckDispatchQueue dispatchQueue,
                                  RandomCheckDispatchService dispatchService) {
        this.dispatchQueue = dispatchQueue;
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedRateString = "${fams.randomcheck.dispatch.poll-rate-ms:60000}")
    public void dispatchDueChecks() {
        List<UUID> due = dispatchQueue.dequeueExpired();
        if (due.isEmpty()) return;

        log.info("RandomCheckDispatchJob — dispatching {} due check(s)", due.size());
        for (UUID checkId : due) {
            try {
                dispatchService.dispatch(checkId);
            } catch (Exception e) {
                log.error("Failed to dispatch checkId={}: {}", checkId, e.getMessage(), e);
            }
        }
    }
}
