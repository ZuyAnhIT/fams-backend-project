package com.fams.modules.site.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class CreateSiteRequest {

    @Schema(description = "Site name — must be unique within the tenant", example = "Hanoi Tower Project")
    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be 100 characters or fewer")
    private String name;

    @Schema(description = "Internal site code — must be unique within the tenant (optional)", example = "HN-001")
    @Size(max = 50, message = "Code must be 50 characters or fewer")
    @Pattern(regexp = "^[A-Za-z0-9\\-_]*$",
             message = "Code may only contain letters, digits, hyphens, and underscores")
    private String code;

    @Schema(description = "Optional description", example = "Main construction site for Hanoi Tower")
    private String description;

    @Schema(description = "Physical address of the site", example = "123 Ba Dinh, Hanoi")
    private String address;

    @Schema(description = "Latitude of the site centre (WGS-84)", example = "21.0285")
    @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90")
    @DecimalMax(value = "90.0",  message = "Latitude must be between -90 and 90")
    private Double latitude;

    @Schema(description = "Longitude of the site centre (WGS-84)", example = "105.8542")
    @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
    @DecimalMax(value = "180.0",  message = "Longitude must be between -180 and 180")
    private Double longitude;

    @Schema(description = "Timezone for the site (IANA tz name, default: UTC)", example = "Asia/Ho_Chi_Minh")
    @Size(max = 50, message = "Timezone must be 50 characters or fewer")
    private String timezone;

    @Schema(description = "Check-in/check-out verification tier for this site: "
            + "gps_only (GPS+geofence only) | gps_face (also requires a Face ID match — photo or "
            + "liveness challenge) | gps_face_liveness (requires a passed active-liveness challenge "
            + "specifically). A Shift can override this per-shift. Default gps_only.",
            defaultValue = "gps_only", allowableValues = {"gps_only", "gps_face", "gps_face_liveness"})
    private String checkinPolicy;

    @Schema(description = "When true, the employee-facing check-in map omits the geofence "
            + "polygon shape (current location + site center marker still shown) — for "
            + "security-sensitive sites where HR doesn't want the exact boundary visible to "
            + "employees. Default false (#130, 2026-08-18).", defaultValue = "false")
    private boolean hidePolygonFromEmployee;
}
