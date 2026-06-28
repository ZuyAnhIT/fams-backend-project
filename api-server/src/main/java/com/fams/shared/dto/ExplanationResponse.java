package com.fams.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
@Schema(description = "Confirmation of employee explanation submission")
public class ExplanationResponse {

    @Schema(description = "Record UUID")
    private UUID id;

    @Schema(description = "Employee note/explanation")
    private String employeeNote;

    @Schema(description = "Optional supporting photo URL")
    private String employeePhotoUrl;

    @Schema(description = "When the explanation was recorded")
    private OffsetDateTime updatedAt;
}
