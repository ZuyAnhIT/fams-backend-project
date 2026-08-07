package com.fams.modules.golive.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Replace the step results on a DRAFT go-live record — the whole list, not a partial patch")
public class UpdateGoLiveStepsRequest {

    @NotNull(message = "steps is required")
    @Valid
    private List<GoLiveStepInput> steps;

    @Schema(description = "Set true once every planned step has been executed — informational, does not block approval")
    private boolean completed;
}
