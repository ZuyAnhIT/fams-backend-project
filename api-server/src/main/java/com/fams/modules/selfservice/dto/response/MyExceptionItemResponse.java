package com.fams.modules.selfservice.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
@Schema(description = "One item in the employee's merged 'needs my explanation' inbox — either a "
        + "pending_review check-in or an unresolved violation, tagged by sourceType so the same "
        + "screen can list both without the employee having to know they come from two different "
        + "backend records")
public class MyExceptionItemResponse {

    @Schema(description = "Underlying record UUID (checkin id or violation id, depending on sourceType)")
    private UUID id;

    @Schema(description = "Which record this item came from", example = "checkin",
            allowableValues = {"checkin", "violation"})
    private String sourceType;

    @Schema(description = "checkin: always 'pending_review'. violation: the violation type "
            + "(no_response | location_fail | face_fail | liveness_fail)")
    private String reasonType;

    @Schema(description = "Date the item relates to")
    private LocalDate date;

    @Schema(description = "Human-readable description of why this item needs the employee's attention")
    private String description;

    @Schema(description = "The endpoint the employee must POST to in order to explain this item, "
            + "so the client doesn't need to hardcode the routing per sourceType",
            example = "/api/v1/tenants/{tenantId}/checkin/{id}/explain")
    private String explainEndpoint;

    @Schema(description = "Whether the employee has already submitted an explanation for this still-open item")
    private boolean hasExplanation;

    @Schema(description = "The employee's latest explanation note, allowing the client to edit instead of submitting blindly again")
    private String employeeNote;

    @Schema(description = "Record creation timestamp, used to sort the merged list")
    private OffsetDateTime createdAt;
}
