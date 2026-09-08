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

    @Schema(description = "Logical end of the session. Can be set while checkOutAt remains null "
            + "when the employee missed checkout.")
    private OffsetDateTime sessionClosedAt;

    @Schema(description = "checkout | missing_checkout | admin_closed")
    private String sessionCloseReason;

    @Schema(description = "Absolute deadline for checkout. After this instant an unclosed session becomes missing_checkout")
    private OffsetDateTime sessionExpiresAt;

    @Schema(description = "Absolute scheduled shift-end instant in the site's timezone")
    private OffsetDateTime shiftEndsAt;

    @Schema(description = "Whether time after shiftEndsAt and before sessionExpiresAt is permitted overtime")
    private boolean overtimeAllowed;

    @Schema(description = "True only while the session has neither checkout evidence nor a logical closure")
    private boolean sessionOpen;

    @Schema(description = "Check-out latitude (null until checked out)")
    private Double checkOutLat;

    @Schema(description = "Check-out longitude (null until checked out)")
    private Double checkOutLon;

    @Schema(description = "Check-out GPS accuracy in metres (null until checked out)")
    private Double checkOutAccuracy;

    @Schema(description = "Whether the check-out GPS point was inside the site geofence (null until checked out)")
    private Boolean checkOutInsideGeofence;

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

    @Schema(description = "Human-readable result message for the employee, in Vietnamese "
            + "(matches every other user-facing message in the check-in flow)",
            example = "Đã ghi nhận chấm công vào, nhưng cần HR xem lại (vị trí hoặc xác thực khuôn mặt). "
                    + "Bạn có thể tiếp tục làm việc bình thường.")
    private String message;

    @Schema(description = "Face verification result (null until async job completes)", example = "true")
    private Boolean faceVerified;

    @Schema(description = "Liveness check result (null unless liveness mode enabled)", example = "true")
    private Boolean livenessVerified;

    @Schema(description = "Face similarity score 0.0 – 1.0 (null until async job completes)", example = "0.92")
    private Double faceVerifyScore;

    @Schema(description = "Face verification result AT CHECKOUT (null until checked out or async job completes)", example = "true")
    private Boolean checkoutFaceVerified;

    @Schema(description = "Liveness check result AT CHECKOUT (null unless liveness was required/provided)", example = "true")
    private Boolean checkoutLivenessVerified;

    @Schema(description = "Face similarity score AT CHECKOUT 0.0 – 1.0", example = "0.92")
    private Double checkoutFaceVerifyScore;

    @Schema(description = "Policy tier (gps_only|gps_face|gps_face_liveness) resolved and snapshotted at "
            + "check-in time. Disambiguates a null Face ID field (\"not applicable\") from \"still verifying\". "
            + "Null only for records created before this field existed.")
    private String effectiveCheckinPolicy;

    @Schema(description = "online (submitted live via the App) | offline (arrived through POST .../checkin/sync)")
    private String source;

    @Schema(description = "Employee full name — batch-resolved for list/history responses to avoid a "
            + "client-side N+1 lookup; null on responses that don't resolve it")
    private String employeeName;

    @Schema(description = "Employee code")
    private String employeeCode;

    @Schema(description = "Site name")
    private String siteName;

    @Schema(description = "Employee-submitted explanation note (null until the employee explains the exception)")
    private String employeeNote;

    @Schema(description = "Protected evidence URL for the employee explanation; HR clients must fetch it with authentication")
    private String employeePhotoUrl;
}
