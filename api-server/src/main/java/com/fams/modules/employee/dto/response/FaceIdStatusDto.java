package com.fams.modules.employee.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Face ID enrollment status for an employee")
public class FaceIdStatusDto {

    @Schema(description = "Employee ID — only populated in list endpoints (e.g. pending-review queue), "
            + "null on the single-employee GET/consent/enroll/revoke responses where the caller already knows it",
            nullable = true)
    private UUID employeeId;

    @Schema(description = "Employee code — only populated in list endpoints", nullable = true)
    private String employeeCode;

    @Schema(description = "Employee full name — only populated in list endpoints", nullable = true)
    private String employeeName;

    @Schema(description = "Currently APPROVED enrollment status: not_enrolled, enrolled, revoked. "
            + "Unaffected by an in-review re-enrollment submission — see reviewStatus for that.")
    private String status;

    @Schema(description = "Whether the employee has given consent to use facial biometric data")
    private boolean consentGiven;

    @Schema(description = "When consent was given (UTC)", nullable = true)
    private OffsetDateTime consentGivenAt;

    @Schema(description = "When enrollment was completed (UTC)", nullable = true)
    private OffsetDateTime enrolledAt;

    @Schema(description = "When Face ID was revoked (UTC, null if not revoked)", nullable = true)
    private OffsetDateTime revokedAt;

    @Schema(description = "Review state of the most recent submission: none | pending | rejected",
            example = "pending")
    private String reviewStatus;

    @Schema(description = "Photo count of the pending/last submission, null if none", nullable = true)
    private Integer pendingPhotoCount;

    @Schema(description = "When the pending/last batch was submitted for review (UTC)", nullable = true)
    private OffsetDateTime submittedAt;

    @Schema(description = "Who reviewed the last submission (UTC)", nullable = true)
    private OffsetDateTime reviewedAt;

    @Schema(description = "Why the last submission was rejected, null unless reviewStatus=rejected", nullable = true)
    private String rejectionReason;
}
