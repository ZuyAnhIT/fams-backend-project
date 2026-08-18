package com.fams.modules.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "One entry in the official catalog of notification event types the system "
        + "sends — see GET /api/v1/notification-event-types")
public class NotificationEventTypeResponse {

    @Schema(description = "Event type identifier — the exact string used everywhere else "
            + "(notification.eventType, notification-settings.eventType, template.eventType)",
            example = "RANDOM_CHECK_SENT")
    private String eventType;

    @Schema(description = "Human-readable label, in Vietnamese", example = "Kiểm tra ngẫu nhiên")
    private String label;

    @Schema(description = "Longer description of when this event fires")
    private String description;

    @Schema(description = "System default for in-app notifications if the user hasn't customized this event type")
    private boolean defaultInAppEnabled;

    @Schema(description = "System default for push notifications if the user hasn't customized this event type")
    private boolean defaultPushEnabled;

    @Schema(description = "Priority every notification of this eventType is created with (#89, "
            + "2026-08-17) — low | normal | high | critical", example = "high")
    private String defaultPriority;
}
