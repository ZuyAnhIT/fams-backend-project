package com.fams.modules.employee.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VerifyFaceRequest {

    @NotBlank(message = "photoBase64 is required")
    @Schema(description = "Base64-encoded face photo (JPEG or PNG)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String photoBase64;

    @Schema(description = "Whether liveness detection is required", defaultValue = "false")
    private boolean requiresLiveness;
}
