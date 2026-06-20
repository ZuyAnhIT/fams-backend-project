package com.fams.modules.subscription.dto.request;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class UpdatePlanLimitsRequest {

    /** null clears the limit (sets to unlimited) */
    @Min(value = 1, message = "maxEmployees must be at least 1")
    private Integer maxEmployees;

    @Min(value = 1, message = "maxSites must be at least 1")
    private Integer maxSites;

    @Min(value = 1, message = "maxStorageGb must be at least 1")
    private Integer maxStorageGb;

    @Min(value = 1, message = "maxRandomChecksPerMonth must be at least 1")
    private Integer maxRandomChecksPerMonth;

    /** When true, explicitly clears a limit to unlimited (sets field to null).
     *  Allows distinguishing "omit field" from "set to unlimited". */
    private boolean clearMaxEmployees;
    private boolean clearMaxSites;
    private boolean clearMaxStorageGb;
    private boolean clearMaxRandomChecksPerMonth;
}
