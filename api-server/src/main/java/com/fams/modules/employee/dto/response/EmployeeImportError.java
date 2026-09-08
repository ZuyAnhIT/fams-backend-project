package com.fams.modules.employee.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EmployeeImportError {

    @Schema(description = "1-based physical row number in the Excel file; row 1 is the header", example = "3")
    private int row;

    @Schema(description = "Field that failed validation", example = "email")
    private String field;

    @Schema(description = "Vietnamese error detail", example = "Email không đúng định dạng")
    private String message;
}
