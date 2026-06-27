package com.fams.modules.shift.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;

@Data
@Schema(description = "Request body for creating a shift template")
public class CreateShiftRequest {

    @Schema(description = "Shift name — must be unique within the site", example = "Morning Shift")
    @NotBlank(message = "Shift name is required")
    @Size(max = 100, message = "Name must be 100 characters or fewer")
    private String name;

    @Schema(description = "Shift start time (HH:mm)", example = "08:00")
    @NotNull(message = "startTime is required")
    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @Schema(description = "Shift end time (HH:mm)", example = "17:00")
    @NotNull(message = "endTime is required")
    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    @Schema(description = "Set to true for overnight shifts where endTime is on the next calendar day",
            example = "false", defaultValue = "false")
    private boolean allowOvernight = false;
}
