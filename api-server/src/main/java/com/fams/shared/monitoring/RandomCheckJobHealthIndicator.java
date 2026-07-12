package com.fams.shared.monitoring;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Component("randomCheckJob")
public class RandomCheckJobHealthIndicator extends AbstractHealthIndicator {

    private static final int STALE_THRESHOLD_MINUTES = 10;

    private static final String JOB_DISPATCH = "RandomCheckDispatchJob";
    private static final String JOB_NO_RESPONSE = "NoResponseViolationJob";

    private final ScheduledJobMonitor monitor;

    public RandomCheckJobHealthIndicator(ScheduledJobMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        boolean allHealthy = true;

        allHealthy &= checkJob(builder, JOB_DISPATCH);
        allHealthy &= checkJob(builder, JOB_NO_RESPONSE);

        if (allHealthy) {
            builder.up();
        } else {
            builder.down();
        }
    }

    private boolean checkJob(Health.Builder builder, String jobName) {
        Optional<ScheduledJobStatus> statusOpt = monitor.getStatus(jobName);

        if (statusOpt.isEmpty()) {
            builder.withDetail(jobName, "not_yet_run");
            return true;
        }

        ScheduledJobStatus status = statusOpt.get();

        if (ScheduledJobMonitor.STATUS_ERROR.equals(status.getLastStatus())) {
            builder.withDetail(jobName + "_status", "ERROR")
                   .withDetail(jobName + "_error", status.getErrorMessage())
                   .withDetail(jobName + "_last_run_at", status.getLastRunAt().toString());
            return false;
        }

        long minutesSinceLastRun = ChronoUnit.MINUTES.between(status.getLastRunAt(), OffsetDateTime.now());
        if (minutesSinceLastRun > STALE_THRESHOLD_MINUTES) {
            builder.withDetail(jobName + "_status", "STALE")
                   .withDetail(jobName + "_minutes_since_last_run", minutesSinceLastRun)
                   .withDetail(jobName + "_last_run_at", status.getLastRunAt().toString());
            return false;
        }

        builder.withDetail(jobName + "_status", "OK")
               .withDetail(jobName + "_last_run_at", status.getLastRunAt().toString());
        return true;
    }
}
