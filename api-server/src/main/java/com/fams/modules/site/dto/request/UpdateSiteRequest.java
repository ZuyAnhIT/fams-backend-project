package com.fams.modules.site.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class UpdateSiteRequest {

    @Schema(description = "New site name — must be unique within the tenant", example = "Hanoi Tower Phase 2")
    @Size(max = 100, message = "Name must be 100 characters or fewer")
    private String name;

    @Schema(description = "New internal site code — must be unique within the tenant", example = "HN-002")
    @Size(max = 50, message = "Code must be 50 characters or fewer")
    @Pattern(regexp = "^[A-Za-z0-9\\-_]*$",
             message = "Code may only contain letters, digits, hyphens, and underscores")
    private String code;

    @Schema(description = "Set to true to explicitly clear the code field")
    private boolean clearCode;

    @Schema(description = "New description")
    private String description;

    @Schema(description = "New physical address")
    private String address;

    @Schema(description = "New latitude (WGS-84)", example = "21.0285")
    @DecimalMin(value = "-90.0",  message = "Latitude must be between -90 and 90")
    @DecimalMax(value = "90.0",   message = "Latitude must be between -90 and 90")
    private Double latitude;

    @Schema(description = "New longitude (WGS-84)", example = "105.8542")
    @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
    @DecimalMax(value = "180.0",  message = "Longitude must be between -180 and 180")
    private Double longitude;

    @Schema(description = "Vietnam business timezone (fixed)", example = "Asia/Ho_Chi_Minh",
            allowableValues = {"Asia/Ho_Chi_Minh"})
    @Size(max = 50, message = "Timezone must be 50 characters or fewer")
    private String timezone;

    @Schema(description = "New status: 'active' or 'inactive'", example = "inactive",
            allowableValues = {"active", "inactive"})
    @Pattern(regexp = "^(active|inactive)$", message = "Status must be 'active' or 'inactive'")
    private String status;

    @Schema(description = "New check-in/check-out verification tier: gps_only | gps_face | "
            + "gps_face_liveness. Omit to leave unchanged.", nullable = true,
            allowableValues = {"gps_only", "gps_face", "gps_face_liveness"})
    @Pattern(regexp = "^(gps_only|gps_face|gps_face_liveness)$",
            message = "checkinPolicy must be 'gps_only', 'gps_face', or 'gps_face_liveness'")
    private String checkinPolicy;

    @Schema(description = "When true, the employee-facing check-in map omits the geofence "
            + "polygon shape (current location + site center marker still shown). Omit to leave "
            + "unchanged. (#130, 2026-08-18)", nullable = true)
    private Boolean hidePolygonFromEmployee;
}
