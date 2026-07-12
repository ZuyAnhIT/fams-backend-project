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
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        log.info("Attendance summary job starting for date={}", yesterday);
        try {
            attendanceSummaryService.recomputeForDate(yesterday);
            log.info("Attendance summary job completed for date={}", yesterday);
            jobMonitor.recordSuccess(JOB_NAME);
        } catch (Exception e) {
            log.error("Attendance summary job failed for date={}: {}", yesterday, e.getMessage(), e);
            jobMonitor.recordFailure(JOB_NAME, e);
        }
    }
}
