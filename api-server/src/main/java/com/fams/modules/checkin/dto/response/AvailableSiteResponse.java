package com.fams.modules.checkin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "A site the employee is assigned to and may check in at today")
public class AvailableSiteResponse {

    @Schema(description = "Assignment ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID assignmentId;

    @Schema(description = "Employee's role at this site", example = "worker")
    private String assignmentRole;

    @Schema(description = "Site information")
    private SiteInfo site;

    @Schema(description = "Shift the employee is scheduled for, null if not linked to a shift")
    private ShiftInfo shift;

    @Schema(description = "Active geofence for the site, null if not configured")
    private GeofenceInfo geofence;

    @Data
    @Builder
    @Schema(description = "Site details")
    public static class SiteInfo {
        @Schema(description = "Site UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        private UUID id;

        @Schema(description = "Site name", example = "Hanoi Office Tower")
        private String name;

        @Schema(description = "Site code", example = "HN-001")
        private String code;

        @Schema(description = "Site address", example = "123 Tran Hung Dao, Hanoi")
        private String address;

        @Schema(description = "Latitude (WGS-84)", example = "21.0285")
        private Double latitude;

        @Schema(description = "Longitude (WGS-84)", example = "105.8542")
        private Double longitude;

        @Schema(description = "Site timezone", example = "Asia/Ho_Chi_Minh")
        private String timezone;
    }

    @Data
    @Builder
    @Schema(description = "Shift details")
    public static class ShiftInfo {
        @Schema(description = "Shift UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        private UUID id;

        @Schema(description = "Shift name", example = "Morning Shift")
        private String name;

        @Schema(description = "Shift start time", example = "08:00:00")
        private LocalTime startTime;

        @Schema(description = "Shift end time", example = "17:00:00")
        private LocalTime endTime;

        @Schema(description = "Whether shift spans overnight", example = "false")
        private boolean allowOvernight;

        @Schema(description = "Minutes before shift start that check-in is allowed", example = "15")
        private int earlyCheckinMinutes;

        @Schema(description = "Minutes after shift end that check-out is allowed", example = "30")
        private int lateCheckoutMinutes;
    }

    @Data
    @Builder
    @Schema(description = "Geofence details")
    public static class GeofenceInfo {
        @Schema(description = "Geofence UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        private UUID id;

        @Schema(description = "Polygon coordinates as [longitude, latitude] pairs")
        private List<List<Double>> coordinates;

        @Schema(description = "Additional buffer in metres beyond polygon boundary", example = "50")
        private int bufferMeters;
    }
}
