package com.fams.modules.site.dto.response;

import com.fams.modules.geofence.dto.response.GeofenceResponse;
import com.fams.modules.shift.dto.response.ShiftResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Full site profile including geofence, shifts, and active assignment summary")
public class SiteDetailResponse {

    @Schema(description = "Site UUID")
    private UUID id;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Site name", example = "Hanoi Tower Project")
    private String name;

    @Schema(description = "Internal site code", example = "HN-001")
    private String code;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Physical address")
    private String address;

    @Schema(description = "Latitude (WGS-84)", example = "21.0285")
    private Double latitude;

    @Schema(description = "Longitude (WGS-84)", example = "105.8542")
    private Double longitude;

    @Schema(description = "IANA timezone name", example = "Asia/Ho_Chi_Minh")
    private String timezone;

    @Schema(description = "Status: active or inactive", example = "active")
    private String status;

    @Schema(description = "Whether check-in at this site requires a passing Face ID verification", example = "false")
    private boolean requireFaceIdCheckin;

    @Schema(description = "UUID of the user who created the site")
    private UUID createdBy;

    @Schema(description = "Active geofence polygon for this site, or null if none has been defined")
    private GeofenceResponse geofence;

    @Schema(description = "Active shift templates configured for this site, ordered by start time")
    private List<ShiftResponse> shifts;

    @Schema(description = "Number of currently active employee assignments at this site")
    private int activeAssignmentCount;

    @Schema(description = "Creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private OffsetDateTime updatedAt;
}
