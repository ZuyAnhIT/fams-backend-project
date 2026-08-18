package com.fams.modules.randomcheck.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Optional reason for cancelling a scheduled check (#99, 2026-08-18). "
        + "Body itself is optional — omit entirely to cancel without a reason.")
public class CancelScheduledCheckRequest {

    @Schema(description = "Why this check is being cancelled — shown to HR later when reviewing "
            + "the cancellation trail.", example = "Nhân viên đã nghỉ việc trước ca này")
    private String reason;
}
