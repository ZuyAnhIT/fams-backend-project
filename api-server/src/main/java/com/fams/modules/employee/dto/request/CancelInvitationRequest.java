package com.fams.modules.employee.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CancelInvitationRequest {

    @Schema(description = "Optional free-text reason for cancelling the invitation, shown later in the "
            + "invitation list/audit trail.", example = "Ứng viên đã từ chối offer")
    @Size(max = 500, message = "Reason must be at most 500 characters")
    private String reason;
}
