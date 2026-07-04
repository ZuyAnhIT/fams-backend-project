package com.fams.modules.violation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Violation summary for HR list view")
public class ViolationListResponse {

    @Schema(description = "Violation UUID", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private UUID id;

    @Schema(description = "Employee UUID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private UUID employeeId;

    @Schema(description = "Site UUID", example = "b2c3d4e5-f6a7-8901-bcde-f01234567891")
    private UUID siteId;

    @Schema(description = "Violation type", example = "no_response",
            allowableValues = {"no_response", "location_fail", "face_fail", "liveness_fail"})
    private String violationType;

    @Schema(description = "Date of the check that triggered the violation", example = "2026-07-04")
    private LocalDate checkDate;

    @Schema(description = "Description of the violation")
    private String description;

    @Schema(description = "Whether the violation has been resolved", example = "false")
    private boolean resolved;

    @Schema(description = "When the violation was resolved (null if unresolved)")
    private OffsetDateTime resolvedAt;

    @Schema(description = "Employee-submitted explanation note")
    private String employeeNote;

    @Schema(description = "URL of photo submitted by employee as evidence")
    private String employeePhotoUrl;

    @Schema(description = "When the violation record was created")
    private OffsetDateTime createdAt;
}
