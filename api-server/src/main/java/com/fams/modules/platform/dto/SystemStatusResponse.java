package com.fams.modules.platform.dto;

import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Value
@Builder
public class SystemStatusResponse {

    String overallHealth;
    Map<String, HealthComponentStatus> healthComponents;
    List<JobStatusItem> jobs;
    long activeTenantCount;
    long faceVerifyQueueDepth;
    long dispatchQueueDepth;
    OffsetDateTime generatedAt;

    /** FE feedback (2026-08-06): healthComponents previously mixed a top-level String "status"
     *  key (redundant with overallHealth) into the SAME map as the per-component objects — every
     *  entry is now this uniform shape instead, no stray non-object value in the map. */
    @Value
    @Builder
    public static class HealthComponentStatus {
        String status;
        Map<String, Object> details;
    }

    @Value
    @Builder
    public static class JobStatusItem {
        String jobName;
        String description;
        /** OK, ERROR, or NEVER_RUN (FE feedback 2026-08-06 — previously a job with no DB row was
         *  simply absent from this list, indistinguishable from "doesn't exist"). */
        String lastStatus;
        OffsetDateTime lastRunAt;
        Long lastRunDurationMs;
        String errorMessage;
        /** Best-effort next scheduled fire time — exact for cron-based jobs (computed from the
         *  cron expression, independent of run history), an estimate of lastRunAt + rate for
         *  fixed-rate jobs, null if a fixed-rate job has never run (no reference point to add
         *  the rate to). */
        OffsetDateTime expectedNextRunAt;
        int staleThresholdMinutes;
        /** True when lastStatus=OK but the job hasn't run recently enough (past
         *  staleThresholdMinutes) — distinguishes "healthy" from "quietly stopped running" per
         *  FE feedback 2026-08-06, since a stuck/disabled job never throws, it just stops firing. */
        boolean stale;
    }
}
