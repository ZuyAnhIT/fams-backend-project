package com.fams.modules.checkin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Full check-in detail for HR dispute resolution — includes embedded employee, site, and shift context")
public class CheckinDetailResponse {

    // ── Core record fields ────────────────────────────────────────────────────

    @Schema(description = "Check-in record UUID")
    private UUID id;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Status: valid | pending_review | rejected")
    private String status;

    @Schema(description = "Human-readable status message")
    private String message;

    @Schema(description = "GPS risk score 0.0 – 1.0 (higher = more suspicious)")
    private double gpsRiskScore;

    @Schema(description = "Device identifier used for check-in")
    private String deviceId;

    // ── Check-in evidence ─────────────────────────────────────────────────────

    @Schema(description = "Check-in timestamp (UTC)")
    private OffsetDateTime checkInAt;

    @Schema(description = "Check-in latitude")
    private double checkInLat;

    @Schema(description = "Check-in longitude")
    private double checkInLon;

    @Schema(description = "Check-in GPS accuracy in metres")
    private Double checkInAccuracy;

    @Schema(description = "Whether check-in GPS was inside the site geofence")
    private boolean checkInInsideGeofence;

    // ── Check-out evidence ────────────────────────────────────────────────────

    @Schema(description = "Check-out timestamp (null if not yet checked out)")
    private OffsetDateTime checkOutAt;

    @Schema(description = "Check-out latitude (null if not yet checked out)")
    private Double checkOutLat;

    @Schema(description = "Check-out longitude (null if not yet checked out)")
    private Double checkOutLon;

    @Schema(description = "Check-out GPS accuracy in metres")
    private Double checkOutAccuracy;

    @Schema(description = "Whether check-out GPS was inside the site geofence")
    private Boolean checkOutInsideGeofence;

    @Schema(description = "Minutes worked (null until checked out)")
    private Integer workMinutes;

    // ── Embedded context ──────────────────────────────────────────────────────

    @Schema(description = "Employee who performed the check-in")
    private EmployeeInfo employee;

    @Schema(description = "Site where the check-in was recorded")
    private SiteInfo site;

    @Schema(description = "Shift linked to this check-in (null if unlinked)")
    private ShiftInfo shift;

    @Schema(description = "Record creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Record last-updated timestamp")
    private OffsetDateTime updatedAt;

    // ── Nested DTOs ───────────────────────────────────────────────────────────

    @Data
    @Builder
    @Schema(description = "Employee summary")
    public static class EmployeeInfo {
        private UUID id;
        private String firstName;
        private String lastName;
        private String employeeCode;
        private String position;
        private String department;
    }

    @Data
    @Builder
    @Schema(description = "Site summary")
    public static class SiteInfo {
        private UUID id;
        private String name;
        private String code;
        private String address;
        private Double latitude;
        private Double longitude;
        private String timezone;
    }

    @Data
    @Builder
    @Schema(description = "Shift summary")
    public static class ShiftInfo {
        private UUID id;
        private String name;
        private LocalTime startTime;
        private LocalTime endTime;
        private boolean allowOvernight;
        private int earlyCheckinMinutes;
        private int lateCheckoutMinutes;
    }
}
