package com.fams.modules.randomcheck.job;

import com.fams.modules.randomcheck.service.ScheduledCheckGeneratorService;
import com.fams.shared.monitoring.ScheduledJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

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

    /** Continuously ensures schedules exist. The initial delay repairs a missed daily run after
     * every restart; the fixed delay also picks up same-day assignment/configuration changes. */
    @Scheduled(fixedDelayString = "${fams.randomcheck.scheduler.fixed-delay-ms:60000}",
            initialDelayString = "${fams.randomcheck.scheduler.initial-delay-ms:5000}")
    public void generateDailyChecks() {
        long startedAt = System.currentTimeMillis();
        OffsetDateTime now = OffsetDateTime.now();
        try {
            int count = generatorService.ensureSchedulesForCurrentLocalDates(now);
            if (count > 0) {
                log.info("RandomCheckSchedulerJob — created {} missing scheduled check(s)", count);
            }
            jobMonitor.recordSuccess(JOB_NAME, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("RandomCheckSchedulerJob failed: {}", e.getMessage(), e);
            jobMonitor.recordFailure(JOB_NAME, System.currentTimeMillis() - startedAt, e);
        }
    }
}
