package com.fams.modules.platform.controller;

import com.fams.modules.platform.dto.SystemStatusResponse;
import com.fams.modules.randomcheck.redis.RandomCheckDispatchQueue;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.monitoring.ScheduledJobMonitor;
import com.fams.shared.monitoring.ScheduledJobStatus;
import com.fams.shared.monitoring.ScheduledJobStatusRepository;
import com.fams.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.SystemHealth;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
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
    private final ScheduledJobMonitor jobMonitor;
    private final ScheduledJobStatusRepository jobStatusRepository;
    private final TenantRepository tenantRepository;
    private final RandomCheckDispatchQueue dispatchQueue;
    private final StringRedisTemplate redisTemplate;

    public SystemStatusController(HealthEndpoint healthEndpoint,
                                   ScheduledJobMonitor jobMonitor,
                                   ScheduledJobStatusRepository jobStatusRepository,
                                   TenantRepository tenantRepository,
                                   RandomCheckDispatchQueue dispatchQueue,
                                   StringRedisTemplate redisTemplate) {
        this.healthEndpoint = healthEndpoint;
        this.jobMonitor = jobMonitor;
        this.jobStatusRepository = jobStatusRepository;
        this.tenantRepository = tenantRepository;
        this.dispatchQueue = dispatchQueue;
        this.redisTemplate = redisTemplate;
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
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/system-status")
    public ResponseEntity<ApiResponse<SystemStatusResponse>> getSystemStatus() {
        HealthComponent health = healthEndpoint.health();
        String overallHealth = health.getStatus().getCode();
        Map<String, Object> healthComponents = flattenHealth(health);

        List<ScheduledJobStatus> allJobs = jobStatusRepository.findAll();
        List<SystemStatusResponse.JobStatusItem> jobItems = allJobs.stream()
                .map(j -> SystemStatusResponse.JobStatusItem.builder()
                        .jobName(j.getJobName())
                        .lastStatus(j.getLastStatus())
                        .lastRunAt(j.getLastRunAt())
                        .errorMessage(j.getErrorMessage())
                        .build())
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

    private Map<String, Object> flattenHealth(HealthComponent component) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", component.getStatus().getCode());
        if (component instanceof SystemHealth systemHealth) {
            systemHealth.getComponents().forEach((name, comp) -> {
                Map<String, Object> compMap = new LinkedHashMap<>();
                compMap.put("status", comp.getStatus().getCode());
                if (comp instanceof org.springframework.boot.actuate.health.Health h
                        && h.getDetails() != null && !h.getDetails().isEmpty()) {
                    compMap.put("details", h.getDetails());
                }
                map.put(name, compMap);
            });
        }
        return map;
    }
}
