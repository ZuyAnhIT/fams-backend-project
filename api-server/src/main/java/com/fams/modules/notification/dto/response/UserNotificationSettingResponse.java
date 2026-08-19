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

    @Schema(description = "Event type identifier", example = "RANDOM_CHECK_SENT")
    private String eventType;

    @Schema(description = "Human-readable label for this event type, in Vietnamese — so FE doesn't "
            + "need its own hardcoded lookup per eventType (added 2026-08-05)", example = "Kiểm tra ngẫu nhiên")
    private String label;

    @Schema(description = "Whether in-app notifications are enabled for this event type", example = "true")
    private boolean inAppEnabled;

    @Schema(description = "Whether push notifications are enabled for this event type", example = "true")
    private boolean pushEnabled;

    @Schema(description = "#141 (2026-08-19): true if this eventType is priority=critical — the "
            + "user cannot disable inAppEnabled/pushEnabled for it (backend rejects with 422 "
            + "MANDATORY_NOTIFICATION if attempted). FE should disable the toggle rather than let "
            + "the user hit the error.", example = "false")
    private boolean mandatory;

    @Schema(description = "False if the user has never explicitly saved a setting for this event "
            + "type — inAppEnabled/pushEnabled above are then the system default, not a user choice "
            + "(added 2026-08-05, see GET /me/notification-settings now always returning every known "
            + "event type instead of only ones the user configured)")
    private boolean customized;

    @Schema(description = "Timestamp when the setting was last updated (null if never customized)")
    private OffsetDateTime updatedAt;
}
