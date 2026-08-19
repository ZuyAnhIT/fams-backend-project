package com.fams.modules.site.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class SiteResponse {

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

    @Schema(description = "Check-in/check-out verification tier: gps_only | gps_face | gps_face_liveness",
            example = "gps_only")
    private String checkinPolicy;

    @Schema(description = "When true, the employee-facing check-in map omits the geofence "
            + "polygon shape (#130, 2026-08-18)", example = "false")
    private boolean hidePolygonFromEmployee;

    @Schema(description = "UUID of the user who created the site")
    private UUID createdBy;

    @Schema(description = "Creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private OffsetDateTime updatedAt;
}
