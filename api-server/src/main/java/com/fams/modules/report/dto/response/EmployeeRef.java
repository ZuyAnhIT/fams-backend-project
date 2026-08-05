package com.fams.modules.report.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/** Minimal employee identity for report rows that list many employees by ID (site presence,
 *  absent lists) — added 2026-08-05 so FE doesn't have to batch-resolve names/codes itself for
 *  every ID in these lists, same reasoning as the batch name hydration already done for
 *  scheduled-check/checkin list responses elsewhere in the system. */
@Data
@Builder
@Schema(description = "Minimal employee identity — id, display name, and employee code")
public class EmployeeRef {

    @Schema(description = "Employee UUID")
    private UUID employeeId;

    @Schema(description = "Employee full name")
    private String employeeName;

    @Schema(description = "Employee code")
    private String employeeCode;
}
