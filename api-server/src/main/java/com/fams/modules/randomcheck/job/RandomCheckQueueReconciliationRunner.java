package com.fams.modules.randomcheck.job;

import com.fams.modules.randomcheck.service.RandomCheckQueueReconciliationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Re-populates the Redis dispatch queue from the scheduled_checks table once on every application
 * startup — see RandomCheckQueueReconciliationService for why this is needed and why it's also
 * run periodically (RandomCheckQueueReconciliationJob), not just here.
 */
@Slf4j
@Component
public class RandomCheckQueueReconciliationRunner implements ApplicationRunner {

    private final RandomCheckQueueReconciliationService reconciliationService;

    public RandomCheckQueueReconciliationRunner(RandomCheckQueueReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int count = reconciliationService.reconcile();
            log.info("RandomCheckQueueReconciliationRunner — re-enqueued {} pending check(s) into the "
                    + "Redis dispatch queue on startup", count);
        } catch (Exception e) {
            log.error("RandomCheckQueueReconciliationRunner failed — dispatch queue may be missing "
                    + "entries until the next restart: {}", e.getMessage(), e);
        }
    }
}
