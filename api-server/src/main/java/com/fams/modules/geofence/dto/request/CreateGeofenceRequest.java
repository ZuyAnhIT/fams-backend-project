package com.fams.modules.geofence.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Request body for creating a geofence polygon for a site")
public class CreateGeofenceRequest {

    @Schema(
        description = "Polygon boundary as an ordered list of [longitude, latitude] pairs (GeoJSON order). " +
                      "Must have at least 4 points; the last point must equal the first to close the ring.",
        example = "[[105.0,21.0],[106.0,21.0],[106.0,22.0],[105.0,22.0],[105.0,21.0]]"
    )
    @NotNull(message = "Coordinates are required")
    @Size(min = 4, message = "A polygon requires at least 4 coordinate pairs (3 vertices + closing point)")
    private List<List<Double>> coordinates;

    @Schema(description = "Optional buffer distance in metres added around the polygon for check-in tolerance",
            example = "50", defaultValue = "0")
    @Min(value = 0, message = "bufferMeters must be 0 or greater")
    private int bufferMeters = 0;
}
