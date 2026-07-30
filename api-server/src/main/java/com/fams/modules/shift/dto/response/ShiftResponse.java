package com.fams.modules.shift.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Shift template associated with a site")
public class ShiftResponse {

    @Schema(description = "Shift UUID")
    private UUID id;

    @Schema(description = "Site UUID")
    private UUID siteId;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Shift name", example = "Morning Shift")
    private String name;

    @Schema(description = "Shift start time (HH:mm)", example = "08:00")
    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @Schema(description = "Shift end time (HH:mm)", example = "17:00")
    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    @Schema(description = "True when the shift spans midnight (endTime is on the next calendar day)")
    private boolean allowOvernight;

    @Schema(description = "Whether overtime is permitted for this shift")
    private boolean allowOvertime;

    @Schema(description = "Minutes before startTime that a check-in is accepted", example = "15")
    private int earlyCheckinMinutes;

    @Schema(description = "Minutes after endTime that a checkout is accepted", example = "30")
    private int lateCheckoutMinutes;

    @Schema(description = "Status: active or inactive", example = "active")
    private String status;

    @Schema(description = "Check-in/check-out verification tier override for this shift; null means "
            + "it inherits the site's policy", nullable = true,
            allowableValues = {"gps_only", "gps_face", "gps_face_liveness"})
    private String checkinPolicyOverride;

    @Schema(description = "Number of assignments (active or historical) that have ever referenced this shift.", example = "0")
    private long assignmentHistoryCount;

    @Schema(description = "Whether DELETE would succeed right now — true only when assignmentHistoryCount is 0. Use this to enable/disable the delete button without a trial call.", example = "true")
    private boolean canDelete;

    @Schema(description = "UUID of the user who created this shift")
    private UUID createdBy;

    @Schema(description = "Creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private OffsetDateTime updatedAt;
}
