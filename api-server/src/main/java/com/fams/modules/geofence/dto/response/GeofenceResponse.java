package com.fams.modules.geofence.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Geofence polygon associated with a site")
public class GeofenceResponse {

    @Schema(description = "Geofence UUID")
    private UUID id;

    @Schema(description = "Site UUID this geofence belongs to")
    private UUID siteId;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Polygon as an ordered list of [longitude, latitude] pairs (GeoJSON order). " +
                          "The ring is closed: last point equals first point.")
    private List<List<Double>> coordinates;

    @Schema(description = "Buffer distance in metres around the polygon", example = "50")
    private int bufferMeters;

    @Schema(description = "Computed polygon area in square metres. Approximate — derived via an " +
                          "equirectangular projection at the polygon's mean latitude (shoelace formula), " +
                          "which is accurate to well under 1% for site-scale boundaries (up to a few km²).",
            example = "12450.75")
    private Double areaSqm;

    @Schema(description = "Optional reason provided for this change. Always null for the initial creation " +
                          "(task 56); may be present on update versions (task 57).")
    private String changeReason;

    @Schema(description = "Status: 'active' (current boundary) or 'superseded' (replaced by a newer version)",
            example = "active")
    private String status;

    @Schema(description = "UUID of the user who created this geofence version")
    private UUID createdBy;

    @Schema(description = "Display name of the user who created this geofence version, resolved from " +
                          "createdBy. Null if the user could not be resolved.")
    private String createdByName;

    @Schema(description = "Creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private OffsetDateTime updatedAt;
}
