package com.fams.modules.randomcheck.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;
import java.util.List;

@Getter
@Setter
public class UpdateRandomCheckConfigRequest {

    @Schema(description = "Number of random checks to perform per shift", example = "2")
    @Min(value = 1, message = "checks_per_shift must be at least 1")
    @Max(value = 10, message = "checks_per_shift cannot exceed 10")
    private Integer checksPerShift;

    @Schema(description = "Minimum minutes between consecutive checks", example = "60")
    @Min(value = 0, message = "min_interval_minutes cannot be negative")
    private Integer minIntervalMinutes;

    @Schema(description = "Earliest time a check may be sent (HH:mm)", example = "08:00")
    private LocalTime allowedStartTime;

    @Schema(description = "Latest time a check may be sent (HH:mm)", example = "17:00")
    private LocalTime allowedEndTime;

    @Schema(
        description = "Verification mode",
        example = "location_only",
        allowableValues = {"location_only", "location_face", "location_face_liveness"}
    )
    @Pattern(
        regexp = "location_only|location_face|location_face_liveness",
        message = "check_mode must be one of: location_only, location_face, location_face_liveness"
    )
    private String checkMode;

    @Schema(description = "Role names at site that are subject to random checks", example = "[\"supervisor\", \"employee\"]")
    private List<@NotBlank String> applicableRoles;

    @Schema(description = "Seconds the employee has to respond before a violation is raised", example = "300")
    @Min(value = 30, message = "response_window_seconds must be at least 30")
    private Integer responseWindowSeconds;

    @Schema(description = "Whether this configuration is active", example = "true")
    private Boolean isActive;
}
