package com.fams.modules.employee.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RejectFaceEnrollmentRequest {

    @NotBlank(message = "reason is required")
    @Schema(description = "Human-readable reason shown to the employee", example = "Ảnh bị mờ, vui lòng chụp lại trong nơi đủ sáng")
    private String reason;
}
