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

    @Schema(description = "Logical session end; set without checkOutAt for a missing checkout")
    private OffsetDateTime sessionClosedAt;

    @Schema(description = "checkout | missing_checkout | admin_closed")
    private String sessionCloseReason;

    @Schema(description = "Absolute checkout deadline calculated at check-in")
    private OffsetDateTime sessionExpiresAt;

    @Schema(description = "Absolute scheduled shift-end instant")
    private OffsetDateTime shiftEndsAt;

    @Schema(description = "Whether the session permits overtime after shift end")
    private boolean overtimeAllowed;

    @Schema(description = "Whether this session can still be checked out")
    private boolean sessionOpen;

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

    // ── Face ID / liveness evidence ───────────────────────────────────────────

    @Schema(description = "Face verification result at check-in (null = not yet resolved by the async worker; "
            + "always null if effectiveCheckinPolicy was gps_only)")
    private Boolean faceVerified;

    @Schema(description = "Liveness check result at check-in")
    private Boolean livenessVerified;

    @Schema(description = "Face similarity score at check-in, 0.0 – 1.0")
    private Double faceVerifyScore;

    @Schema(description = "Face verification result at check-out")
    private Boolean checkoutFaceVerified;

    @Schema(description = "Liveness check result at check-out")
    private Boolean checkoutLivenessVerified;

    @Schema(description = "Face similarity score at check-out, 0.0 – 1.0")
    private Double checkoutFaceVerifyScore;

    // ── Audit / provenance ─────────────────────────────────────────────────────

    @Schema(description = "Policy tier (gps_only|gps_face|gps_face_liveness) resolved and snapshotted AT CHECK-IN "
            + "TIME — disambiguates a null Face ID result (\"not applicable under this policy\") from "
            + "\"still verifying\". Null only for records created before this field existed.")
    private String effectiveCheckinPolicy;

    @Schema(description = "online (submitted live via the App) | offline (arrived through POST .../checkin/sync)")
    private String source;

    @Schema(description = "Idempotency key supplied by the client for offline sync — null for online records")
    private java.util.UUID clientNonce;

    @Schema(description = "HR override reason, if this record's status was ever changed via POST .../override")
    private String note;

    @Schema(description = "User UUID of the HR/admin who last overrode this record's status, if any")
    private java.util.UUID overriddenBy;

    @Schema(description = "Timestamp of the last override, if any")
    private OffsetDateTime overriddenAt;

    @Schema(description = "Employee explanation submitted for this disputed check-in")
    private String employeeNote;

    @Schema(description = "Authenticated API URL for the private explanation image, when present")
    private String employeePhotoUrl;

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
