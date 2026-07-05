package com.fams.modules.checkin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Check-in session record")
public class CheckinResponse {

    @Schema(description = "Check-in record UUID")
    private UUID id;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Site UUID")
    private UUID siteId;

    @Schema(description = "Employee UUID")
    private UUID employeeId;

    @Schema(description = "Assignment UUID")
    private UUID assignmentId;

    @Schema(description = "Shift UUID (null if not linked to a shift)")
    private UUID shiftId;

    @Schema(description = "Status: valid | pending_review | rejected", example = "valid")
    private String status;

    @Schema(description = "Check-in timestamp (UTC)")
    private OffsetDateTime checkInAt;

    @Schema(description = "Check-in latitude", example = "21.0285")
    private double checkInLat;

    @Schema(description = "Check-in longitude", example = "105.8542")
    private double checkInLon;

    @Schema(description = "Check-in GPS accuracy in metres", example = "12.5")
    private Double checkInAccuracy;

    @Schema(description = "Whether the check-in GPS point was inside the site geofence", example = "true")
    private boolean checkInInsideGeofence;

    @Schema(description = "Check-out timestamp (null until checked out)")
    private OffsetDateTime checkOutAt;

    @Schema(description = "Minutes worked (null until checked out)")
    private Integer workMinutes;

    @Schema(description = "GPS risk score 0.0 – 1.0 (higher = more suspicious)", example = "0.0")
    private double gpsRiskScore;

    @Schema(description = "Device identifier")
    private String deviceId;

    @Schema(description = "Record creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Record last-updated timestamp")
    private OffsetDateTime updatedAt;

    @Schema(description = "Human-readable result message for the employee",
            example = "Check-in recorded. Your location was outside the site geofence — HR will review your attendance.")
    private String message;

    @Schema(description = "Face verification result (null until async job completes)", example = "true")
    private Boolean faceVerified;

    @Schema(description = "Liveness check result (null unless liveness mode enabled)", example = "true")
    private Boolean livenessVerified;

    @Schema(description = "Face similarity score 0.0 – 1.0 (null until async job completes)", example = "0.92")
    private Double faceVerifyScore;
}
