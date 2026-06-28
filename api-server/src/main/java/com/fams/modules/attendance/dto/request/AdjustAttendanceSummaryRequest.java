package com.fams.modules.attendance.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AdjustAttendanceSummaryRequest {

    @Min(value = 0, message = "totalWorkMinutes must be >= 0")
    private Integer totalWorkMinutes;

    @Pattern(regexp = "present|incomplete", message = "status must be 'present' or 'incomplete'")
    private String status;

    private Boolean late;

    @Min(value = 0, message = "lateMinutes must be >= 0")
    private Integer lateMinutes;

    private Boolean earlyLeave;

    @Min(value = 0, message = "earlyLeaveMinutes must be >= 0")
    private Integer earlyLeaveMinutes;

    @Min(value = 0, message = "otMinutes must be >= 0")
    private Integer otMinutes;

    private Boolean missingCheckout;

    @NotBlank(message = "reason is required")
    private String reason;
}
