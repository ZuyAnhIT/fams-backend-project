package com.fams.modules.randomcheck.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;
import java.util.List;

@Getter
@Setter
public class CreateRandomCheckConfigRequest {

    @Schema(description = "Number of random checks to perform per shift", example = "2")
    @Min(value = 1, message = "checks_per_shift must be at least 1")
    @Max(value = 10, message = "checks_per_shift cannot exceed 10")
    @NotNull
    private Integer checksPerShift;

    @Schema(description = "Minimum minutes between consecutive checks", example = "60")
    @Min(value = 0, message = "min_interval_minutes cannot be negative")
    @NotNull
    private Integer minIntervalMinutes;

    @Schema(description = "Earliest time a check may be sent (HH:mm)", example = "08:00")
    @NotNull(message = "allowed_start_time is required")
    private LocalTime allowedStartTime;

    @Schema(description = "Latest time a check may be sent (HH:mm)", example = "17:00")
    @NotNull(message = "allowed_end_time is required")
    private LocalTime allowedEndTime;

    @Schema(
        description = "Verification mode for random checks. " +
                      "location_only: GPS geofence check only. " +
                      "location_face: GPS + async AI face match (requires employee to have an enrolled Face ID; " +
                      "employees without an enrolled profile will automatically receive a face_fail violation). " +
                      "location_face_liveness: GPS + face match + liveness anti-spoofing (same enrollment requirement).",
        example = "location_only",
        allowableValues = {"location_only", "location_face", "location_face_liveness"}
    )
    @NotBlank
    @Pattern(
        regexp = "location_only|location_face|location_face_liveness",
        message = "check_mode must be one of: location_only, location_face, location_face_liveness"
    )
    private String checkMode;

    @Schema(description = "Role names at site that are subject to random checks — matched against "
            + "Assignment.role, currently only 'worker' or 'supervisor' (enforced by @Pattern + a DB CHECK "
            + "constraint); any other value here can never match a real assignment.",
            example = "[\"worker\", \"supervisor\"]")
    @NotNull
    private List<@NotBlank String> applicableRoles;

    @Schema(description = "Seconds the employee has to respond before a violation is raised", example = "300")
    @Min(value = 30, message = "response_window_seconds must be at least 30")
    @NotNull
    private Integer responseWindowSeconds;

    @Schema(description = "Number of failed/no_response checks in a calendar month that surfaces "
            + "\"exceeds threshold\" on the HR monthly attendance report — informational escalation signal "
            + "only, never auto-adjusts pay. Defaults to 3 if omitted.", example = "3")
    @Min(value = 1, message = "failure_escalation_threshold must be at least 1")
    private Integer failureEscalationThreshold;
}
