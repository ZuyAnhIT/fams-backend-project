package com.fams.modules.randomcheck.job;

import com.fams.modules.randomcheck.service.ScheduledCheckGeneratorService;
import com.fams.shared.monitoring.ScheduledJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
public class RandomCheckSchedulerJob {

    private static final String JOB_NAME = "RandomCheckSchedulerJob";

    private final ScheduledCheckGeneratorService generatorService;
    private final ScheduledJobMonitor jobMonitor;

    public RandomCheckSchedulerJob(ScheduledCheckGeneratorService generatorService,
                                   ScheduledJobMonitor jobMonitor) {
        this.generatorService = generatorService;
        this.jobMonitor = jobMonitor;
    }

    /**
     * Generates scheduled random checks for all active assignments each day.
     * Runs at 00:01 AM every day so checks are ready before any shift begins.
     * The generation is idempotent — re-running on the same day is safe.
     */
    @Scheduled(cron = "${fams.randomcheck.scheduler.cron:0 1 0 * * *}")
    public void generateDailyChecks() {
        long startedAt = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        log.info("RandomCheckSchedulerJob — generating checks for {}", today);
        try {
            int count = generatorService.generateForDate(today);
            log.info("RandomCheckSchedulerJob — created {} scheduled checks for {}", count, today);
            jobMonitor.recordSuccess(JOB_NAME, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("RandomCheckSchedulerJob — failed for {}: {}", today, e.getMessage(), e);
            jobMonitor.recordFailure(JOB_NAME, System.currentTimeMillis() - startedAt, e);
        }
    }
}
