package com.fams.modules.site.dto.response;

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

    @Schema(description = "UUID of the user who created the site")
    private UUID createdBy;

    @Schema(description = "Active geofence for this site — populated when geofence module is implemented (task 56)")
    private Object geofence;

    @Schema(description = "Shift templates configured for this site — populated when shift module is implemented (tasks 59-62)")
    private List<Object> shifts;

    @Schema(description = "Number of currently active employee assignments — populated when assignment module is implemented (task 63)")
    private int activeAssignmentCount;

    @Schema(description = "Creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private OffsetDateTime updatedAt;
}
