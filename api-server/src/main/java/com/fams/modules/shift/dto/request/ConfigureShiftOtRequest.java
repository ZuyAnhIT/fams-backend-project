package com.fams.modules.shift.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "Request body for configuring OT and check-in/out tolerance on a shift template. " +
                      "All fields are optional — only provided fields are changed.")
public class ConfigureShiftOtRequest {

    @Schema(description = "Whether overtime is permitted for this shift. " +
                          "Omit to keep the current setting.")
    private Boolean allowOvertime;

    @Schema(description = "Minutes before startTime that a check-in is accepted. " +
                          "Omit to keep the current setting.", example = "15")
    @Min(value = 0, message = "earlyCheckinMinutes must be 0 or greater")
    private Integer earlyCheckinMinutes;

    @Schema(description = "Minutes after endTime that a checkout is accepted. " +
                          "Omit to keep the current setting.", example = "30")
    @Min(value = 0, message = "lateCheckoutMinutes must be 0 or greater")
    private Integer lateCheckoutMinutes;

    @Schema(description = "#60 (docs/api/backend-feature-audit-2026-08-07.md): max OT minutes allowed per day "
            + "before AttendanceSummary.otDailyLimitExceeded is flagged for HR — warn-only, never blocks a "
            + "checkout or caps otMinutes. Omit to keep the current setting; pass clearMaxOtMinutesPerDay=true "
            + "to remove the limit (unlimited).", example = "120")
    @Min(value = 0, message = "maxOtMinutesPerDay must be 0 or greater")
    private Integer maxOtMinutesPerDay;

    @Schema(description = "Whether to clear maxOtMinutesPerDay back to unlimited (null). Ignored if " +
                          "maxOtMinutesPerDay is also provided in the same request.")
    private boolean clearMaxOtMinutesPerDay;

    @Schema(description = "Max OT minutes allowed per ISO week (Mon-Sun) before " +
            "AttendanceSummary.otWeeklyLimitExceeded is flagged — warn-only, same as maxOtMinutesPerDay. " +
            "Omit to keep the current setting; pass clearMaxOtMinutesPerWeek=true to remove the limit.",
            example = "600")
    @Min(value = 0, message = "maxOtMinutesPerWeek must be 0 or greater")
    private Integer maxOtMinutesPerWeek;

    @Schema(description = "Whether to clear maxOtMinutesPerWeek back to unlimited (null). Ignored if " +
                          "maxOtMinutesPerWeek is also provided in the same request.")
    private boolean clearMaxOtMinutesPerWeek;
}
