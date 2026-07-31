package com.fams.modules.attendance.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UnlockAttendanceSummaryRequest {

    @NotBlank(message = "reason is required")
    private String reason;
}
