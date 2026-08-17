package com.fams.modules.shift.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    @Schema(description = "Override the site's check-in/check-out verification tier for this specific "
            + "shift (e.g. a night shift can require gps_face_liveness even at an otherwise gps_only "
            + "site). Omit/null to inherit the site's policy.", nullable = true,
            allowableValues = {"gps_only", "gps_face", "gps_face_liveness"})
    private String checkinPolicyOverride;

    // Field deliberately NOT named isDefault: Lombok's accessor generation for a boolean field
    // already prefixed with "is" produces getter isDefault()/setter setDefault() — two DIFFERENT
    // property names by Jackson's own convention, so a request body's "isDefault" would silently
    // fail to bind. @JsonProperty pins the wire name explicitly (same pattern as SavedFilter).
    @JsonProperty("isDefault")
    @Schema(description = "Set as the default shift for this site — pre-selected when creating an "
            + "assignment. Setting true clears the previous default, if any. Purely advisory, does "
            + "not affect check-in/check-out behavior.", example = "false", defaultValue = "false")
    private boolean defaultShift = false;
}
