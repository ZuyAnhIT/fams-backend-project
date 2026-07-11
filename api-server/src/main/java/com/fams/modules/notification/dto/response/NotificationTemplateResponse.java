package com.fams.modules.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "A notification template")
public class NotificationTemplateResponse {

  @Schema(description = "Template UUID")
  private UUID id;

  @Schema(description = "Tenant UUID")
  private UUID tenantId;

  @Schema(description = "Event type identifier", example = "RANDOM_CHECK_DISPATCHED")
  private String eventType;

  @Schema(description = "Locale code", example = "vi")
  private String locale;

  @Schema(description = "Title template with {variable} placeholders")
  private String titleTemplate;

  @Schema(description = "Body template with {variable} placeholders")
  private String bodyTemplate;

  @Schema(description = "Template creation timestamp")
  private OffsetDateTime createdAt;

  @Schema(description = "Template last update timestamp")
  private OffsetDateTime updatedAt;
}
