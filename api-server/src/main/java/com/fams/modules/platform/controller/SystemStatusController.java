package com.fams.modules.platform.controller;

import com.fams.modules.attendance.service.AttendanceSummaryService;
import com.fams.modules.notification.dto.response.NotificationDeliveryLogResponse;
import com.fams.modules.notification.entity.NotificationDeliveryLog;
import com.fams.modules.notification.repository.NotificationDeliveryLogRepository;
import com.fams.modules.notification.specification.NotificationDeliveryLogSpecification;
import com.fams.modules.platform.dto.SystemStatusResponse;
import com.fams.modules.randomcheck.redis.RandomCheckDispatchQueue;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.monitoring.ScheduledJobCatalog;
import com.fams.shared.monitoring.ScheduledJobMonitor;
import com.fams.shared.monitoring.ScheduledJobStatus;
import com.fams.shared.monitoring.ScheduledJobStatusRepository;
import com.fams.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.SystemHealth;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "Platform", description = "Platform-level system administration endpoints")
@RestController
@RequestMapping("/api/v1/platform")
public class SystemStatusController {

    private static final String FACE_VERIFY_QUEUE_KEY = "fams:ai:face_verify_jobs";

    private final HealthEndpoint healthEndpoint;
    // Job status is read directly via jobStatusRepository (unioned with ScheduledJobCatalog) —
    // no longer goes through ScheduledJobMonitor.getStatus() per job since the 2026-08-06
    // catalog rework, so no instance of it is needed here anymore (ScheduledJobMonitor.STATUS_OK
    // below is a static constant reference, not an instance call).
    private final ScheduledJobStatusRepository jobStatusRepository;
    private final TenantRepository tenantRepository;
    private final RandomCheckDispatchQueue dispatchQueue;
    private final StringRedisTemplate redisTemplate;
    private final AttendanceSummaryService attendanceSummaryService;
    private final NotificationDeliveryLogRepository deliveryLogRepository;

    public SystemStatusController(HealthEndpoint healthEndpoint,
                                   ScheduledJobStatusRepository jobStatusRepository,
                                   TenantRepository tenantRepository,
                                   RandomCheckDispatchQueue dispatchQueue,
                                   StringRedisTemplate redisTemplate,
                                   AttendanceSummaryService attendanceSummaryService,
                                   NotificationDeliveryLogRepository deliveryLogRepository) {
        this.healthEndpoint = healthEndpoint;
        this.jobStatusRepository = jobStatusRepository;
        this.tenantRepository = tenantRepository;
        this.dispatchQueue = dispatchQueue;
        this.redisTemplate = redisTemplate;
        this.attendanceSummaryService = attendanceSummaryService;
        this.deliveryLogRepository = deliveryLogRepository;
    }

    @Operation(
        summary = "System status overview",
        description = "Aggregates actuator health, per-job scheduler status, active tenant count, and Redis queue depths. " +
                      "Requires PLATFORM_ADMIN role."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status aggregated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — PLATFORM_ADMIN only")
    })
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('system:read')")
    @GetMapping("/system-status")
    public ResponseEntity<ApiResponse<SystemStatusResponse>> getSystemStatus() {
        HealthComponent health = healthEndpoint.health();
        String overallHealth = health.getStatus().getCode();
        Map<String, SystemStatusResponse.HealthComponentStatus> healthComponents = flattenHealth(health);

        // Union the static catalog (every known @Scheduled job) with whatever rows actually
        // exist in scheduled_job_status — found via FE feedback (2026-08-06): previously this
        // only returned jobs that had a DB row, so a job that was misconfigured/never fired since
        // deploy was simply absent, indistinguishable from "this job doesn't exist".
        Map<String, ScheduledJobStatus> statusByName = jobStatusRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(ScheduledJobStatus::getJobName, s -> s));
        OffsetDateTime now = OffsetDateTime.now();
        List<SystemStatusResponse.JobStatusItem> jobItems = ScheduledJobCatalog.ALL.stream()
                .map(catalogEntry -> buildJobStatusItem(catalogEntry, statusByName.get(catalogEntry.jobName()), now))
                .toList();

        long activeTenants = tenantRepository.countByStatusAndDeletedAtIsNull("active");

        Long faceVerifyDepth = redisTemplate.opsForList().size(FACE_VERIFY_QUEUE_KEY);
        long dispatchDepth = dispatchQueue.queueSize();

        SystemStatusResponse response = SystemStatusResponse.builder()
                .overallHealth(overallHealth)
                .healthComponents(healthComponents)
                .jobs(jobItems)
                .activeTenantCount(activeTenants)
                .faceVerifyQueueDepth(faceVerifyDepth != null ? faceVerifyDepth : 0L)
                .dispatchQueueDepth(dispatchDepth)
                .generatedAt(OffsetDateTime.now())
                .build();

        log.info("System status requested by platform admin — overall={}", overallHealth);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Backfill attendance summaries for a date range, across every tenant",
        description = "Re-runs the standard attendance recompute for every day in [from, to] — needed after a "
                      + "migration/code change to the recompute formula (e.g. V79's status-filtering fix, "
                      + "2026-07-31) so historical summary rows computed under the OLD logic get reconciled. "
                      + "Reuses the exact same recompute() logic as real-time/nightly recompute — never a "
                      + "separate SQL formula. Skips any summary already manually adjusted by HR (unchanged, "
                      + "same protection as every other recompute path). Cross-tenant by nature — restricted "
                      + "to PLATFORM_ADMIN, never exposed to a tenant-scoped HR/Admin role."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Backfill completed (see body for per-day success/failure counts)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — PLATFORM_ADMIN only")
    })
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PostMapping("/attendance/backfill")
    public ResponseEntity<ApiResponse<Map<String, Object>>> backfillAttendance(
            @Parameter(description = "Start date, inclusive (yyyy-MM-dd)", required = true)
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date, inclusive (yyyy-MM-dd)", required = true)
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }

        log.info("Platform admin triggered attendance backfill: from={} to={}", from, to);
        AttendanceSummaryService.BackfillResult result = attendanceSummaryService.backfillDateRange(from, to);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", to);
        body.put("daysProcessed", result.daysProcessed());
        body.put("daysFailed", result.daysFailed());

        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @Operation(
        summary = "List notification delivery attempts (dead-letter visibility)",
        description = "Paginated, filterable view of every push/email delivery attempt recorded by " +
                      "FcmClient/UserDeviceService — the only way to see FAILED/FALLBACK_EMAIL_FAILED " +
                      "rows today; they were previously only reachable via direct DB query (audit 2026-08-06). " +
                      "Cross-tenant by nature (NotificationDeliveryLog does not carry tenantId — it's keyed " +
                      "off notificationId/deviceToken), so restricted to PLATFORM_ADMIN like the rest of " +
                      "this controller. Device tokens are masked to the last 6 characters."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Paginated delivery log list"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — PLATFORM_ADMIN only")
    })
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('system:read')")
    @GetMapping("/notifications/delivery-logs")
    public ResponseEntity<ApiResponse<PageResponse<NotificationDeliveryLogResponse>>> listDeliveryLogs(
            @Parameter(description = "Filter by status: SUCCESS, FAILED, FALLBACK_EMAIL_SENT, FALLBACK_EMAIL_FAILED")
                @RequestParam(required = false) String status,
            @Parameter(description = "Filter by channel, e.g. FCM, EMAIL")
                @RequestParam(required = false) String channel,
            @Parameter(description = "Start of date range (ISO-8601)")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "End of date range (ISO-8601)")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (max 200)") @RequestParam(defaultValue = "20") int size) {

        size = Math.min(size, 200);
        Page<NotificationDeliveryLog> result = deliveryLogRepository.findAll(
                NotificationDeliveryLogSpecification.build(status, channel, from, to),
                PageRequest.of(page, size, org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));

        PageResponse<NotificationDeliveryLogResponse> response = PageResponse.from(
                result.map(this::toDeliveryLogResponse));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private NotificationDeliveryLogResponse toDeliveryLogResponse(NotificationDeliveryLog entry) {
        return NotificationDeliveryLogResponse.builder()
                .id(entry.getId())
                .notificationId(entry.getNotificationId())
                .deviceToken(maskToken(entry.getDeviceToken()))
                .channel(entry.getChannel())
                .attemptNumber(entry.getAttemptNumber())
                .status(entry.getStatus())
                .errorMessage(entry.getErrorMessage())
                .createdAt(entry.getCreatedAt())
                .build();
    }

    private String maskToken(String token) {
        if (token == null) return null;
        return token.length() <= 6 ? token : "…" + token.substring(token.length() - 6);
    }

    /** FE feedback (2026-08-06): previously stuffed a top-level String "status" key into the
     *  SAME map as the per-component objects — {"status":"UP","db":{...},"redis":{...}} — a
     *  client can't tell a component name from a scalar without special-casing "status". That
     *  top-level value was always redundant anyway (identical to overallHealth, the actual
     *  aggregate root's own status) — dropped entirely rather than kept as a component, exactly
     *  as FE suggested. Every remaining entry is now a uniform {status, details} object. */
    private Map<String, SystemStatusResponse.HealthComponentStatus> flattenHealth(HealthComponent component) {
        Map<String, SystemStatusResponse.HealthComponentStatus> map = new LinkedHashMap<>();
        if (component instanceof SystemHealth systemHealth) {
            systemHealth.getComponents().forEach((name, comp) -> {
                Map<String, Object> details = null;
                if (comp instanceof org.springframework.boot.actuate.health.Health h
                        && h.getDetails() != null && !h.getDetails().isEmpty()) {
                    details = h.getDetails();
                }
                map.put(name, SystemStatusResponse.HealthComponentStatus.builder()
                        .status(comp.getStatus().getCode())
                        .details(details)
                        .build());
            });
        }
        return map;
    }

    private SystemStatusResponse.JobStatusItem buildJobStatusItem(
            ScheduledJobCatalog.ScheduledJobInfo catalogEntry, ScheduledJobStatus dbStatus, OffsetDateTime now) {

        String lastStatus = dbStatus != null ? dbStatus.getLastStatus() : "NEVER_RUN";
        OffsetDateTime lastRunAt = dbStatus != null ? dbStatus.getLastRunAt() : null;

        boolean stale = false;
        if (ScheduledJobMonitor.STATUS_OK.equals(lastStatus) && lastRunAt != null) {
            long minutesSinceLastRun = ChronoUnit.MINUTES.between(lastRunAt, now);
            stale = minutesSinceLastRun > catalogEntry.staleThresholdMinutes();
        }

        OffsetDateTime expectedNextRunAt = switch (catalogEntry.scheduleType()) {
            // Cron next-fire-time is independent of run history (absolute wall-clock triggers),
            // so this is exact even for a job that has never run — not an estimate.
            case CRON -> CronExpression.parse(catalogEntry.cronExpression()).next(now);
            // Fixed-rate has no absolute schedule — the only reference point we have is the last
            // actual run, so a never-run job's next fire time is genuinely unknown here.
            case FIXED_RATE -> lastRunAt != null
                    ? lastRunAt.plus(java.time.Duration.ofMillis(catalogEntry.fixedRateMs()))
                    : null;
        };

        return SystemStatusResponse.JobStatusItem.builder()
                .jobName(catalogEntry.jobName())
                .description(catalogEntry.description())
                .lastStatus(lastStatus)
                .lastRunAt(lastRunAt)
                .lastRunDurationMs(dbStatus != null ? dbStatus.getLastRunDurationMs() : null)
                .errorMessage(dbStatus != null ? dbStatus.getErrorMessage() : null)
                .expectedNextRunAt(expectedNextRunAt)
                .staleThresholdMinutes(catalogEntry.staleThresholdMinutes())
                .stale(stale)
                .build();
    }
}
