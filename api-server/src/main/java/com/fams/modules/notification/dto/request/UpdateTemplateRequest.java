package com.fams.modules.notification.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body for updating a notification template (all fields optional)")
public class UpdateTemplateRequest {

  @Schema(description = "Event type identifier", example = "RANDOM_CHECK_DISPATCHED")
  private String eventType;

  @Schema(description = "Locale code", example = "vi")
  private String locale;

  @Schema(description = "Title template with {variable} placeholders", example = "Check requested for {studentName}")
  private String titleTemplate;

  @Schema(description = "Body template with {variable} placeholders", example = "Dear {studentName}, a random check has been dispatched.")
  private String bodyTemplate;
}
