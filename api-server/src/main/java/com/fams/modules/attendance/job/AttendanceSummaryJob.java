package com.fams.modules.attendance.job;

import com.fams.modules.attendance.service.AttendanceSummaryService;
import com.fams.shared.monitoring.ScheduledJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

@Slf4j
@Component
public class AttendanceSummaryJob {

    private static final String JOB_NAME = "AttendanceSummaryJob";
    private static final String SAME_DAY_JOB_NAME = "AttendanceSummarySameDayCatchUpJob";

    private final AttendanceSummaryService attendanceSummaryService;
    private final ScheduledJobMonitor jobMonitor;

    public AttendanceSummaryJob(AttendanceSummaryService attendanceSummaryService,
                                ScheduledJobMonitor jobMonitor) {
        this.attendanceSummaryService = attendanceSummaryService;
        this.jobMonitor = jobMonitor;
    }

    /**
     * Runs at 01:00 UTC daily to finalise attendance summaries for the previous calendar day.
     * Covers all timezones by using a ±1 day UTC search window in the service layer.
     * The recompute is idempotent — safe to re-run manually.
     */
    @Scheduled(cron = "0 0 1 * * *")
    public void computePreviousDaySummaries() {
        long startedAt = System.currentTimeMillis();
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        log.info("Attendance summary job starting for date={}", yesterday);
        try {
            attendanceSummaryService.recomputeForDate(yesterday);
            log.info("Attendance summary job completed for date={}", yesterday);
            jobMonitor.recordSuccess(JOB_NAME, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("Attendance summary job failed for date={}: {}", yesterday, e.getMessage(), e);
            jobMonitor.recordFailure(JOB_NAME, System.currentTimeMillis() - startedAt, e);
        }
    }

    /**
     * 2026-08-12 backend readiness assessment: checkin/checkout-triggered recompute
     * (AttendanceSummaryService#recomputeForCheckin, called from CheckinService/OfflineSyncService)
     * is deliberately fire-and-forget — a transient DB hiccup there is caught, logged, and
     * swallowed rather than failing the checkin/checkout itself (correct: a person's ability to
     * clock in must never depend on a secondary summary calculation succeeding). But that meant
     * a same-day failure had NO retry until computePreviousDaySummaries() ran the FOLLOWING
     * night — an HR manager checking "today's" attendance that same afternoon could see stale or
     * missing data with no signal anything went wrong, for up to ~24-48h. This is a lightweight
     * same-day safety net, same idempotent recomputeForDate() the nightly job already uses (safe
     * to re-run, upserts by employee+site+date, skips HR-adjusted rows) — just for TODAY instead
     * of yesterday, every 2 hours rather than nightly, so a transient failure self-heals same-day
     * without needing to poll every checkin/checkout event individually.
     */
    @Scheduled(cron = "0 15 */2 * * *")
    public void catchUpTodaySummaries() {
        long startedAt = System.currentTimeMillis();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try {
            attendanceSummaryService.recomputeForDate(today);
            jobMonitor.recordSuccess(SAME_DAY_JOB_NAME, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("Attendance same-day catch-up job failed for date={}: {}", today, e.getMessage(), e);
            jobMonitor.recordFailure(SAME_DAY_JOB_NAME, System.currentTimeMillis() - startedAt, e);
        }
    }
}
