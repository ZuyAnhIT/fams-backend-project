package com.fams.modules.employee.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
@Schema(description = "Face ID enrollment status for an employee")
public class FaceIdStatusDto {

    @Schema(description = "Enrollment status: not_enrolled, pending, enrolled, revoked")
    private String status;

    @Schema(description = "Whether the employee has given consent to use facial biometric data")
    private boolean consentGiven;

    @Schema(description = "When consent was given (UTC)", nullable = true)
    private OffsetDateTime consentGivenAt;

    @Schema(description = "When enrollment was completed (UTC)", nullable = true)
    private OffsetDateTime enrolledAt;

    @Schema(description = "When Face ID was revoked (UTC, null if not revoked)", nullable = true)
    private OffsetDateTime revokedAt;
}
