package com.fams.modules.golive.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "One checklist step result — mirrors a line item in docs/deployment/go-live-checklist.md")
public class GoLiveStepInput {

    @NotBlank(message = "stepName is required")
    @Schema(description = "Checklist step name/label", example = "Env vars complete (.env)")
    private String stepName;

    @NotBlank(message = "result is required")
    @Schema(description = "PASS, FAIL, or SKIP", example = "PASS")
    private String result;

    @Schema(description = "Free-text note — required in practice for FAIL/SKIP, not enforced server-side")
    private String note;

    @Schema(description = "URL/reference to supporting evidence (screenshot, log, exported report...)")
    private String evidenceUrl;
}
