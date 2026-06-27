package com.fams.modules.geofence.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Request body for updating the active geofence of a site. " +
                      "At least one field must be provided. " +
                      "The update creates a new geofence version and supersedes the current one.")
public class UpdateGeofenceRequest {

    @Schema(
        description = "New polygon boundary as an ordered list of [longitude, latitude] pairs (GeoJSON order). " +
                      "Must have at least 4 points; the last point must equal the first to close the ring. " +
                      "Omit to keep the existing polygon.",
        example = "[[105.0,21.0],[106.0,21.0],[106.0,22.0],[105.0,22.0],[105.0,21.0]]"
    )
    @Size(min = 4, message = "A polygon requires at least 4 coordinate pairs (3 vertices + closing point)")
    private List<List<Double>> coordinates;

    @Schema(description = "New buffer distance in metres. Omit to keep the existing buffer.",
            example = "100")
    @Min(value = 0, message = "bufferMeters must be 0 or greater")
    private Integer bufferMeters;
}
