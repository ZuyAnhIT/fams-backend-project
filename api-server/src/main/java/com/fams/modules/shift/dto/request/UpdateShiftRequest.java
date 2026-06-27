package com.fams.modules.shift.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;

@Data
@Schema(description = "Request body for updating a shift template. All fields are optional — only provided fields are changed.")
public class UpdateShiftRequest {

    @Schema(description = "New shift name — must be unique within the site", example = "Early Morning Shift")
    @Size(max = 100, message = "Name must be 100 characters or fewer")
    private String name;

    @Schema(description = "New shift start time (HH:mm)", example = "07:00")
    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @Schema(description = "New shift end time (HH:mm)", example = "16:00")
    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    @Schema(description = "Set to true for overnight shifts, false for same-day shifts")
    private Boolean allowOvernight;

    @Schema(description = "New status: 'active' or 'inactive'", example = "inactive",
            allowableValues = {"active", "inactive"})
    @Pattern(regexp = "^(active|inactive)$", message = "Status must be 'active' or 'inactive'")
    private String status;
}
