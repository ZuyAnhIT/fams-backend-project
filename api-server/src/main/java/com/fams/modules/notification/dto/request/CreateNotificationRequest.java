package com.fams.modules.notification.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(description = "Request body for creating a notification (internal use)")
public class CreateNotificationRequest {

  @NotNull
  @Schema(description = "Tenant UUID", required = true)
  private UUID tenantId;

  @NotNull
  @Schema(description = "User UUID", required = true)
  private UUID userId;

  @NotBlank
  @Schema(description = "Event type identifier", example = "RANDOM_CHECK_DISPATCHED", required = true)
  private String eventType;

  @NotBlank
  @Schema(description = "Notification title", example = "Random check requested", required = true)
  private String title;

  @Schema(description = "Notification body text")
  private String body;
}
