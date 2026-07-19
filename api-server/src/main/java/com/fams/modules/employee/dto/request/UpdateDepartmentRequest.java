package com.fams.modules.employee.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateDepartmentRequest {

    @Schema(description = "New department name (must be unique within the tenant)", example = "Engineering & Platform")
    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    @Schema(description = "Updated description of the department")
    private String description;
}
