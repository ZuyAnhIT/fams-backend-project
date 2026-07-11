package com.fams.modules.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "A user notification setting for a specific event type")
public class UserNotificationSettingResponse {

    @Schema(description = "Setting UUID")
    private UUID id;

    @Schema(description = "User UUID")
    private UUID userId;

    @Schema(description = "Event type identifier", example = "RANDOM_CHECK_DISPATCHED")
    private String eventType;

    @Schema(description = "Whether in-app notifications are enabled for this event type", example = "true")
    private boolean inAppEnabled;

    @Schema(description = "Whether push notifications are enabled for this event type", example = "true")
    private boolean pushEnabled;

    @Schema(description = "Timestamp when the setting was last updated")
    private OffsetDateTime updatedAt;
}
