package com.fams.modules.notification.constant;

import java.util.List;

/**
 * Single source of truth for every {@code eventType} the system actually sends notifications
 * for — added 2026-08-05 per FE feedback: {@code GET /me/notification-settings} only ever
 * returned rows the user had explicitly configured, so a user who never touched settings saw an
 * empty list with no way to discover what event types even exist. Worse, every DTO's Swagger
 * example (`CreateNotificationRequest`, `UserNotificationSettingResponse`, etc.) showed
 * {@code "RANDOM_CHECK_DISPATCHED"} as the example value — a string that has never actually
 * existed in the codebase (the real, only-ever-used constant is
 * {@link com.fams.modules.randomcheck.constant.RandomCheckEventTypes#RANDOM_CHECK_SENT}) — so
 * even the documentation was actively misleading FE about what to expect.
 *
 * <p>This catalog is the notification module's own registry, not a reverse dependency on each
 * producing module's constants class (randomcheck, and eventually checkin/attendance/violation
 * when they start sending notifications) — a producing module's own constant is the value that
 * gets passed into {@code NotificationService#createNotification}, and this catalog's {@code
 * eventType} strings must be kept equal to those constants by convention (enforced by the
 * comment next to each entry below, not by a compile-time reference, to avoid the notification
 * module depending on every module that happens to send notifications).
 *
 * <p><b>When a new module starts sending notifications</b> (e.g. checkin escalates to
 * pending_review, a violation is confirmed, an assignment changes): add one entry here with the
 * same string constant the producing service uses. {@link
 * com.fams.modules.notification.service.UserNotificationSettingService#getSettings} and {@code
 * GET /api/v1/notification-event-types} both read this list automatically — no other change
 * needed for the new type to appear in every user's settings screen.
 */
public final class NotificationEventTypeCatalog {

    private NotificationEventTypeCatalog() {
    }

    public static final List<NotificationEventTypeInfo> ALL = List.of(
            new NotificationEventTypeInfo(
                    // Must match com.fams.modules.randomcheck.constant.RandomCheckEventTypes.RANDOM_CHECK_SENT
                    "RANDOM_CHECK_SENT",
                    "Kiểm tra ngẫu nhiên",
                    "Gửi khi hệ thống yêu cầu bạn phản hồi một lượt kiểm tra ngẫu nhiên (vị trí/khuôn mặt).",
                    true,
                    true)
    );

    public record NotificationEventTypeInfo(
            String eventType,
            String label,
            String description,
            boolean defaultInAppEnabled,
            boolean defaultPushEnabled) {
    }
}
