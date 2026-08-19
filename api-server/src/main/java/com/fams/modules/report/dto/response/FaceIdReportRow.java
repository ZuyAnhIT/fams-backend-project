package com.fams.modules.report.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "One row in the Face ID enrollment status report — one per active employee")
public class FaceIdReportRow {

    @Schema(description = "Employee ID", example = "99c0caf2-a929-4255-897c-709a3f743bd2")
    private UUID employeeId;

    @Schema(description = "Employee code", example = "EMP-0042")
    private String employeeCode;

    @Schema(description = "First name", example = "Nguyen")
    private String firstName;

    @Schema(description = "Last name", example = "Van A")
    private String lastName;

    @Schema(description = "Email", example = "nguyen.vana@example.com")
    private String email;

    @Schema(description = "Department", example = "Engineering")
    private String department;

    @Schema(description = "Face ID status: not_enrolled | pending | enrolled | revoked", example = "enrolled")
    private String faceIdStatus;

    @Schema(description = "Whether the employee has given Face ID consent", example = "true")
    private boolean consentGiven;

    @Schema(description = "When consent was given (null if not yet given)", example = "2026-06-01T10:00:00Z")
    private OffsetDateTime consentGivenAt;

    @Schema(description = "When Face ID was enrolled (null if not enrolled)", example = "2026-06-01T10:05:00Z")
    private OffsetDateTime enrolledAt;

    @Schema(description = "When Face ID was revoked (null if not revoked)", example = "null")
    private OffsetDateTime revokedAt;

    @Schema(description = "Review state of the most recent submission: none | pending | rejected", example = "none")
    private String reviewStatus;

    @Schema(description = "When the pending/last batch was submitted for review (null if none)", example = "null")
    private OffsetDateTime submittedAt;

    @Schema(description = "Why the last submission was rejected, null unless reviewStatus=rejected", example = "null")
    private String rejectionReason;

    @Schema(description = "Anti-spoof confidence of the active enrollment (0-1, worst photo in "
            + "the batch) — null if never enrolled (#127, 2026-08-18)", example = "0.87")
    private Double qualityScore;
}
