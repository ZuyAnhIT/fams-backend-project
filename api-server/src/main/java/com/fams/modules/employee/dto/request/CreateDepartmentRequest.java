package com.fams.modules.employee.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateDepartmentRequest {

    @Schema(description = "Department name (must be unique within the tenant)", example = "Engineering")
    @NotBlank(message = "Department name is required")
    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    @Schema(description = "Optional description of the department", example = "Software development team")
    private String description;
}
