package com.fams.modules.notification.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@Schema(description = "A single notification item")
public class NotificationResponse {

  @Schema(description = "Notification UUID")
  private UUID id;

  @Schema(description = "Tenant UUID")
  private UUID tenantId;

  @Schema(description = "User UUID")
  private UUID userId;

  @Schema(description = "Event type identifier", example = "RANDOM_CHECK_DISPATCHED")
  private String eventType;

  @Schema(description = "Notification title", example = "Random check requested")
  private String title;

  @Schema(description = "Notification body text")
  private String body;

  @Schema(description = "Structured, machine-readable payload for deep-linking — e.g. "
          + "{\"checkId\": \"...\", \"siteId\": \"...\", \"expiresAt\": \"...\"} for RANDOM_CHECK_SENT. "
          + "Null for notification types that don't carry structured data.")
  private Map<String, Object> metadata;

  @JsonProperty("isRead")
  @Schema(description = "Whether the notification has been read", example = "false")
  private boolean isRead;

  @Schema(description = "Timestamp when the notification was read (null if unread)")
  private OffsetDateTime readAt;

  @Schema(description = "Notification creation timestamp")
  private OffsetDateTime createdAt;
}
