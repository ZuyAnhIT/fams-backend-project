package com.fams.modules.golive.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "Create a new go-live checklist run for a tenant")
public class CreateGoLiveRecordRequest {

    @NotNull(message = "tenantId is required")
    private UUID tenantId;

    @NotBlank(message = "environment is required")
    @Schema(description = "Target environment", example = "production")
    private String environment;

    @NotBlank(message = "buildVersion is required")
    @Schema(description = "Build/release identifier being verified", example = "2026.08.06-1")
    private String buildVersion;

    @Schema(description = "Checklist step results — may also be submitted incrementally via PATCH")
    @Valid
    private List<GoLiveStepInput> steps;
}
