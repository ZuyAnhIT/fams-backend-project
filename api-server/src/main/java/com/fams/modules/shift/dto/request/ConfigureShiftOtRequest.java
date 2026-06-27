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
}
