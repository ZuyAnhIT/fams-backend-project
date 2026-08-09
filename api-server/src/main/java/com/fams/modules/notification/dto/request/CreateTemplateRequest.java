package com.fams.modules.notification.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request body for creating a notification template")
public class CreateTemplateRequest {

  @NotBlank
  @Schema(description = "Event type identifier", example = "RANDOM_CHECK_SENT", requiredMode = Schema.RequiredMode.REQUIRED)
  private String eventType;

  @Schema(description = "Locale code (default 'vi')", example = "vi")
  private String locale = "vi";

  @NotBlank
  @Schema(description = "Title template with {variable} placeholders", example = "Check requested for {studentName}", requiredMode = Schema.RequiredMode.REQUIRED)
  private String titleTemplate;

  @NotBlank
  @Schema(description = "Body template with {variable} placeholders", example = "Dear {studentName}, a random check has been dispatched.", requiredMode = Schema.RequiredMode.REQUIRED)
  private String bodyTemplate;
}
