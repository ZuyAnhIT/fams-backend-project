package com.fams.modules.subscription.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Resource limits configured for a subscription plan")
public class PlanLimitsResponse {

    @Schema(description = "Limits record UUID")
    private UUID id;

    @Schema(description = "UUID of the plan these limits belong to")
    private UUID planId;

    @Schema(description = "Maximum number of employees (null = unlimited)", example = "500")
    private Integer maxEmployees;

    @Schema(description = "Maximum number of sites (null = unlimited)", example = "10")
    private Integer maxSites;

    @Schema(description = "Maximum storage in GB (null = unlimited)", example = "100")
    private Integer maxStorageGb;

    @Schema(description = "Maximum random checks per month (null = unlimited)", example = "50")
    private Integer maxRandomChecksPerMonth;

    @Schema(description = "Maximum report exports per month (null = unlimited) — #135, 2026-08-19", example = "100")
    private Integer maxExportsPerMonth;

    @Schema(description = "Last update timestamp (UTC)")
    private OffsetDateTime updatedAt;
}
