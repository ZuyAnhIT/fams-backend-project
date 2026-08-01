package com.fams.modules.randomcheck.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class RandomCheckConfigResponse {

    @Schema(description = "Config UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Site UUID — null means this is the tenant-level default")
    private UUID siteId;

    @Schema(description = "Number of random checks per shift", example = "2")
    private int checksPerShift;

    @Schema(description = "Minimum minutes between checks", example = "60")
    private int minIntervalMinutes;

    @Schema(description = "Earliest allowed check time", example = "08:00")
    private LocalTime allowedStartTime;

    @Schema(description = "Latest allowed check time", example = "17:00")
    private LocalTime allowedEndTime;

    @Schema(description = "Verification mode", example = "location_only")
    private String checkMode;

    @Schema(description = "Roles that are subject to random checks")
    private List<String> applicableRoles;

    @Schema(description = "Seconds the employee has to respond", example = "300")
    private int responseWindowSeconds;

    @Schema(description = "Failed/no_response checks in a month that surfaces \"exceeds threshold\" on the "
            + "HR monthly attendance report", example = "3")
    private int failureEscalationThreshold;

    @Schema(description = "Whether the config is active", example = "true")
    private boolean isActive;

    @Schema(description = "UUID of the user who created this config")
    private UUID createdBy;

    @Schema(description = "Creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private OffsetDateTime updatedAt;

    @Schema(description = "Only set by GET .../sites/{siteId}/effective — which config actually "
            + "applies to this site: 'site_override' or 'tenant_default'. Null on every other endpoint.",
            example = "tenant_default")
    private String resolvedFrom;
}
