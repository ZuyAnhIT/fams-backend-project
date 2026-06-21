package com.fams.modules.employee.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ChangeEmployeeStatusRequest {

    @Schema(description = "New status: active, inactive, or terminated", example = "inactive")
    @NotBlank(message = "Status is required")
    @Pattern(regexp = "^(active|inactive|terminated)$", message = "Status must be active, inactive, or terminated")
    private String status;
}
