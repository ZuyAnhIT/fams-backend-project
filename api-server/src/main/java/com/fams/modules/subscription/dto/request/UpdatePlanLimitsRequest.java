package com.fams.modules.subscription.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "Update resource limits for a plan. Pass null (or the corresponding clearXxx=true) to make a limit unlimited.")
public class UpdatePlanLimitsRequest {

    @Schema(description = "Maximum number of employees allowed (null = unlimited, min 1)", example = "500")
    @Min(value = 1, message = "maxEmployees must be at least 1")
    private Integer maxEmployees;

    @Schema(description = "Maximum number of sites allowed (null = unlimited, min 1)", example = "10")
    @Min(value = 1, message = "maxSites must be at least 1")
    private Integer maxSites;

    @Schema(description = "Maximum storage in GB (null = unlimited, min 1)", example = "100")
    @Min(value = 1, message = "maxStorageGb must be at least 1")
    private Integer maxStorageGb;

    @Schema(description = "Maximum random checks per month (null = unlimited, min 1)", example = "50")
    @Min(value = 1, message = "maxRandomChecksPerMonth must be at least 1")
    private Integer maxRandomChecksPerMonth;

    @Schema(description = "Maximum report exports per month (null = unlimited, min 1) — #135, 2026-08-19", example = "100")
    @Min(value = 1, message = "maxExportsPerMonth must be at least 1")
    private Integer maxExportsPerMonth;

    @Schema(description = "When true, sets maxEmployees to unlimited regardless of the maxEmployees field value")
    private boolean clearMaxEmployees;

    @Schema(description = "When true, sets maxSites to unlimited regardless of the maxSites field value")
    private boolean clearMaxSites;

    @Schema(description = "When true, sets maxStorageGb to unlimited regardless of the maxStorageGb field value")
    private boolean clearMaxStorageGb;

    @Schema(description = "When true, sets maxRandomChecksPerMonth to unlimited regardless of the maxRandomChecksPerMonth field value")
    private boolean clearMaxRandomChecksPerMonth;

    @Schema(description = "When true, sets maxExportsPerMonth to unlimited regardless of the maxExportsPerMonth field value")
    private boolean clearMaxExportsPerMonth;
}
