package com.fams.modules.employee.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EmployeeImportError {

    @Schema(description = "1-based row number in the Excel file (excluding header)", example = "3")
    private int row;

    @Schema(description = "Field that failed validation", example = "email")
    private String field;

    @Schema(description = "Error detail", example = "Must be a valid email address")
    private String message;
}
