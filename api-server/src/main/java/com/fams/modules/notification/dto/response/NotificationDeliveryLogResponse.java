package com.fams.modules.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "A single delivery attempt record for a push/email notification channel")
public class NotificationDeliveryLogResponse {

    @Schema(description = "Delivery log UUID")
    private UUID id;

    @Schema(description = "Notification UUID this attempt belongs to (may be null for a push-only send where in-app was disabled)")
    private UUID notificationId;

    @Schema(description = "Masked device token — only the last 6 characters are shown", example = "…a1b2c3")
    private String deviceToken;

    @Schema(description = "Delivery channel", example = "FCM")
    private String channel;

    @Schema(description = "Which attempt this was (1-based, matches FcmClient's retry count)")
    private int attemptNumber;

    @Schema(description = "SUCCESS, FAILED, FALLBACK_EMAIL_SENT, or FALLBACK_EMAIL_FAILED", example = "FAILED")
    private String status;

    @Schema(description = "Error detail when status is FAILED/FALLBACK_EMAIL_FAILED")
    private String errorMessage;

    @Schema(description = "When this attempt was recorded")
    private OffsetDateTime createdAt;
}
