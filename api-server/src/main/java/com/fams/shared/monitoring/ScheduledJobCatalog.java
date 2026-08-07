package com.fams.shared.monitoring;

import java.util.List;

/**
 * Single source of truth for every {@code @Scheduled} job that exists in the codebase — added
 * 2026-08-06 per FE feedback on System Health: {@code GET /api/v1/platform/system-status}
 * previously only ever returned jobs found in {@code scheduled_job_status}, which only gets a
 * row once a job has run at least once. A job that is misconfigured (bean not picked up,
 * property disabled it, cron expression evaluates to "never") or simply hasn't fired yet since
 * deploy was completely invisible — indistinguishable from "this job doesn't exist" rather than
 * "this job exists but hasn't run", which is exactly the failure mode a health dashboard exists
 * to catch. {@code SystemStatusController} now unions this catalog with the DB table so every
 * known job always appears, with an explicit {@code NEVER_RUN} status when it has no row yet.
 *
 * <p><b>When adding a new {@code @Scheduled} job</b>: add one entry here with the same
 * {@code JOB_NAME} constant the job class uses when calling {@code ScheduledJobMonitor}. This is
 * the only place that needs updating for the new job to appear in {@code /system-status}.
 */
public final class ScheduledJobCatalog {

    private ScheduledJobCatalog() {
    }

    public enum ScheduleType {
        /** Cron expression — next fire time is computed exactly via {@link
         *  org.springframework.scheduling.support.CronExpression}. */
        CRON,
        /** Fixed rate in milliseconds — next fire time is estimated as lastRunAt + rate (only
         *  meaningful once the job has run at least once; unknown before the first run since we
         *  don't track application-boot time centrally). */
        FIXED_RATE
    }

    public static final List<ScheduledJobInfo> ALL = List.of(
            new ScheduledJobInfo(
                    "AttendanceSummaryJob",
                    "Nightly catch-up recompute of attendance summaries for every tenant's check-ins from the previous day.",
                    ScheduleType.CRON, "0 0 1 * * *", null,
                    26 * 60),
            new ScheduledJobInfo(
                    "RandomCheckSchedulerJob",
                    "Generates the day's scheduled random checks for every active assignment.",
                    ScheduleType.CRON, "0 1 0 * * *", null,
                    26 * 60),
            new ScheduledJobInfo(
                    "RandomCheckDispatchJob",
                    "Polls the Redis dispatch queue and sends due random-check notifications.",
                    ScheduleType.FIXED_RATE, null, 60_000L,
                    10),
            new ScheduledJobInfo(
                    "NoResponseViolationJob",
                    "Marks expired 'sent' random checks as no_response and raises a violation.",
                    ScheduleType.FIXED_RATE, null, 120_000L,
                    10),
            new ScheduledJobInfo(
                    "RandomCheckQueueReconciliationJob",
                    "Periodic self-heal: re-enqueues any 'pending' check missing from the Redis dispatch queue.",
                    ScheduleType.FIXED_RATE, null, 300_000L,
                    15),
            new ScheduledJobInfo(
                    "DataRetentionJob",
                    "Weekly purge of old delivery logs, read notifications, revoked face embeddings, and old biometric photos.",
                    ScheduleType.CRON, "0 0 3 * * SUN", null,
                    8 * 24 * 60),
            new ScheduledJobInfo(
                    "SubscriptionExpirationJob",
                    "Daily sweep that expires subscriptions past their expiresAt and suspends the tenant.",
                    ScheduleType.CRON, "0 0 0 * * *", null,
                    26 * 60)
    );

    public record ScheduledJobInfo(
            String jobName,
            String description,
            ScheduleType scheduleType,
            String cronExpression,
            Long fixedRateMs,
            int staleThresholdMinutes) {
    }
}
