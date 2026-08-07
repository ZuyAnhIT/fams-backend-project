package com.fams.modules.golive.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Approve or reject a go-live record")
public class ApproveGoLiveRecordRequest {

    @Schema(description = "Free-text approval/rejection note")
    private String note;
}
