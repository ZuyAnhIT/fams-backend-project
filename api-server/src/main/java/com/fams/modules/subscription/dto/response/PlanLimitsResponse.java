package com.fams.modules.subscription.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class PlanLimitsResponse {
    private UUID id;
    private UUID planId;
    /** null means unlimited */
    private Integer maxEmployees;
    /** null means unlimited */
    private Integer maxSites;
    /** null means unlimited (GB) */
    private Integer maxStorageGb;
    /** null means unlimited */
    private Integer maxRandomChecksPerMonth;
    private OffsetDateTime updatedAt;
}
