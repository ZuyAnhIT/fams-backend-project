package com.fams.modules.employee.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "Non-mutating validation result for an employee import workbook")
public class EmployeeImportValidationResponse {

    @Schema(description = "True when the workbook structure and every data row are valid")
    private boolean valid;

    @Schema(description = "Total non-empty data rows, excluding the header")
    private int totalRows;

    @Schema(description = "Rows that can be imported")
    private int validRows;

    @Schema(description = "Rows that contain at least one validation error")
    private int invalidRows;

    @Schema(description = "Header and per-row validation errors in Vietnamese")
    private List<EmployeeImportError> errors;
}
