package com.fams.modules.violation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Full violation detail including linked check response evidence")
public class ViolationDetailResponse {

    @Schema(description = "Violation UUID")
    private UUID id;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Employee UUID")
    private UUID employeeId;

    @Schema(description = "Site UUID")
    private UUID siteId;

    @Schema(description = "Scheduled check UUID (null for non-random-check violations)")
    private UUID scheduledCheckId;

    @Schema(description = "Check response UUID (null if employee never responded)")
    private UUID checkResponseId;

    @Schema(description = "Check-in UUID — set when this violation came from a regular check-in "
            + "at a Face-ID-required site failing verification (null for random-check violations)")
    private UUID checkinId;

    @Schema(description = "Violation type",
            allowableValues = {"no_response", "location_fail", "face_fail", "liveness_fail", "face_verify_timeout"})
    private String violationType;

    @Schema(description = "Date of the check that triggered the violation")
    private LocalDate checkDate;

    @Schema(description = "Violation description")
    private String description;

    @Schema(description = "Whether the violation has been resolved")
    private boolean resolved;

    @Schema(description = "When the violation was resolved (null if unresolved)")
    private OffsetDateTime resolvedAt;

    @Schema(description = "UUID of the user who resolved the violation")
    private UUID resolvedBy;

    @Schema(description = "Resolution outcome (null while unresolved)",
            allowableValues = {"confirmed", "dismissed"})
    private String resolution;

    @Schema(description = "HR note/reason attached to the resolution")
    private String resolutionReason;

    @Schema(description = "Reporting flag only; does not automatically deduct attendance or payroll")
    private boolean affectsAttendance;

    @Schema(description = "Explanation note submitted by employee")
    private String employeeNote;

    @Schema(description = "URL of photo submitted by employee as evidence")
    private String employeePhotoUrl;

    @Schema(description = "When the violation record was created")
    private OffsetDateTime createdAt;

    @Schema(description = "Scheduled check details (present when violation stems from a random check)")
    private ScheduledCheckSummary scheduledCheck;

    @Schema(description = "Employee's check response evidence (present when employee did respond)")
    private CheckResponseEvidence checkResponse;

    @Data
    @Builder
    @Schema(description = "Summary of the scheduled check that triggered this violation")
    public static class ScheduledCheckSummary {

        @Schema(description = "Scheduled check UUID")
        private UUID id;

        @Schema(description = "When the check was scheduled to be performed")
        private OffsetDateTime scheduledAt;

        @Schema(description = "When the check window expired")
        private OffsetDateTime expiresAt;

        @Schema(description = "Status of the scheduled check")
        private String status;

        @Schema(description = "Zero-based index of this check within the day")
        private int checkIndex;
    }

    @Data
    @Builder
    @Schema(description = "Evidence submitted by employee in their check response")
    public static class CheckResponseEvidence {

        @Schema(description = "Check response UUID")
        private UUID id;

        @Schema(description = "When the employee responded")
        private OffsetDateTime respondedAt;

        @Schema(description = "Latitude reported by employee")
        private BigDecimal latitude;

        @Schema(description = "Longitude reported by employee")
        private BigDecimal longitude;

        @Schema(description = "GPS accuracy in metres")
        private BigDecimal accuracyMeters;

        @Schema(description = "URL of face image submitted by employee")
        private String faceImageUrl;

        @Schema(description = "Liveness score (0.0–1.0)")
        private BigDecimal livenessScore;

        @Schema(description = "Whether the submitted location passed geofence check")
        private boolean locationVerified;

        @Schema(description = "Whether face verification passed")
        private Boolean faceVerified;

        @Schema(description = "Whether liveness check passed")
        private Boolean livenessVerified;

        @Schema(description = "Outcome of the response (pass | fail)")
        private String outcome;

        @Schema(description = "Reason the response failed (null on pass)")
        private String failureReason;
    }
}
