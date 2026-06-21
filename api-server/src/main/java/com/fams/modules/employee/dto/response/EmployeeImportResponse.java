package com.fams.modules.employee.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class EmployeeImportResponse {

    @Schema(description = "Total data rows read from the file (excluding header)")
    private int totalRows;

    @Schema(description = "Number of employees successfully created")
    private int successCount;

    @Schema(description = "Number of rows that failed validation or creation")
    private int failedCount;

    @Schema(description = "Per-row error details for failed rows")
    private List<EmployeeImportError> errors;
}
