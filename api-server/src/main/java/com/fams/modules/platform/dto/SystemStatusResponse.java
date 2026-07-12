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
    Map<String, Object> healthComponents;
    List<JobStatusItem> jobs;
    long activeTenantCount;
    long faceVerifyQueueDepth;
    long dispatchQueueDepth;
    OffsetDateTime generatedAt;

    @Value
    @Builder
    public static class JobStatusItem {
        String jobName;
        String lastStatus;
        OffsetDateTime lastRunAt;
        String errorMessage;
    }
}
