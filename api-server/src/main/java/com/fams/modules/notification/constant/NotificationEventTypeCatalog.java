package com.fams.modules.notification.constant;

import java.util.List;
import java.util.Map;

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

    /** low | normal | high | critical — used to resolve a default {@code priority} for any
     *  eventType not found in {@link #ALL} (should not happen in practice, but keeps
     *  {@link #defaultPriorityFor} total instead of throwing). */
    public static final String FALLBACK_PRIORITY = "normal";

    public static final List<NotificationEventTypeInfo> ALL = List.of(
            new NotificationEventTypeInfo(
                    // Must match com.fams.modules.randomcheck.constant.RandomCheckEventTypes.RANDOM_CHECK_SENT
                    "RANDOM_CHECK_SENT",
                    "Kiểm tra ngẫu nhiên",
                    "Gửi khi hệ thống yêu cầu bạn phản hồi một lượt kiểm tra ngẫu nhiên (vị trí/khuôn mặt).",
                    true,
                    true,
                    // #140 (2026-08-19): bumped from "high" to "critical" — the only mandatory,
                    // time-boxed notification in the system (employee must respond within a short
                    // window or it becomes a violation, see #141's "random_check_request cannot be
                    // disabled" rule) and the only eventType that should trigger the new
                    // priority-gated email fallback in UserDeviceService#sendPush. Before this
                    // catalog had zero "critical" entries, so gating fallback on priority=="critical"
                    // (per #140's Acceptance Criteria) would have silently disabled fallback for
                    // every notification type — decided with the user 2026-08-19 rather than
                    // silently regressing or ignoring the AC.
                    "critical"),
            new NotificationEventTypeInfo(
                    // Must match com.fams.modules.employee.constant.InvitationEventTypes.EMPLOYEE_INVITED
                    "EMPLOYEE_INVITED",
                    "Được mời vào công ty",
                    "Gửi khi bạn được mời tham gia một công ty (chỉ áp dụng nếu bạn đã có tài khoản FAMS).",
                    true,
                    true,
                    "normal"),
            new NotificationEventTypeInfo(
                    // Must match com.fams.modules.employee.constant.InvitationEventTypes.INVITATION_ACCEPTED
                    "INVITATION_ACCEPTED",
                    "Lời mời đã được chấp nhận",
                    "Gửi cho người gửi lời mời khi người được mời chấp nhận tham gia.",
                    true,
                    false,
                    "low"),
            new NotificationEventTypeInfo(
                    // Must match com.fams.modules.rbac.constant.RoleEventTypes.ROLE_ASSIGNED
                    "ROLE_ASSIGNED",
                    "Được gán vai trò mới",
                    "Gửi khi bạn được gán một vai trò (role) mới trong một công ty.",
                    true,
                    false,
                    "normal"),
            new NotificationEventTypeInfo(
                    // Must match com.fams.modules.rbac.constant.RoleEventTypes.ROLE_REVOKED
                    "ROLE_REVOKED",
                    "Bị thu hồi vai trò",
                    "Gửi khi một vai trò (role) bạn đang giữ bị thu hồi trong một công ty.",
                    true,
                    true,
                    "high"),
            new NotificationEventTypeInfo(
                    // Must match com.fams.modules.attendance.constant.AttendanceEventTypes.MISSING_CHECKOUT_EMPLOYEE
                    "MISSING_CHECKOUT_EMPLOYEE",
                    "Quên check-out",
                    "Gửi cho bạn khi hệ thống phát hiện một ngày công trước đó bạn chưa check-out.",
                    true,
                    true,
                    "normal"),
            new NotificationEventTypeInfo(
                    // Must match com.fams.modules.attendance.constant.AttendanceEventTypes.MISSING_CHECKOUT_HR
                    "MISSING_CHECKOUT_HR",
                    "Nhân viên quên check-out",
                    "Gửi cho HR/quản lý khi một nhân viên có ngày công trước đó chưa check-out.",
                    true,
                    false,
                    "normal"),
            new NotificationEventTypeInfo(
                    // Must match com.fams.modules.violation.constant.ViolationEventTypes.RANDOM_CHECK_VIOLATION_EMPLOYEE
                    "RANDOM_CHECK_VIOLATION_EMPLOYEE",
                    "Vi phạm kiểm tra ngẫu nhiên",
                    "Gửi cho bạn khi hệ thống ghi nhận một vi phạm kiểm tra ngẫu nhiên (không phản hồi, sai vị trí, không xác thực được khuôn mặt/người thật).",
                    true,
                    true,
                    "high"),
            new NotificationEventTypeInfo(
                    // Must match com.fams.modules.violation.constant.ViolationEventTypes.RANDOM_CHECK_VIOLATION_HR
                    "RANDOM_CHECK_VIOLATION_HR",
                    "Nhân viên vi phạm kiểm tra ngẫu nhiên",
                    "Gửi cho HR/quản lý khi một nhân viên bị ghi nhận vi phạm kiểm tra ngẫu nhiên.",
                    true,
                    false,
                    "high"),
            new NotificationEventTypeInfo(
                    // Must match com.fams.modules.checkin.constant.CheckinEventTypes.EXPLANATION_SUBMITTED_HR
                    "CHECKIN_EXPLANATION_SUBMITTED_HR",
                    "Nhân viên gửi giải trình check-in",
                    "Gửi cho HR/quản lý khi một nhân viên gửi giải trình cho check-in bị đánh dấu lỗi.",
                    true,
                    false,
                    "normal"),
            new NotificationEventTypeInfo(
                    // Must match com.fams.modules.violation.constant.ViolationEventTypes.VIOLATION_EXPLANATION_SUBMITTED_HR
                    "VIOLATION_EXPLANATION_SUBMITTED_HR",
                    "Nhân viên gửi giải trình vi phạm",
                    "Gửi cho HR/quản lý khi một nhân viên gửi giải trình trực tiếp cho một vi phạm.",
                    true,
                    false,
                    "normal"),
            new NotificationEventTypeInfo(
                    // Must match com.fams.modules.violation.constant.ViolationEventTypes.VIOLATION_DISMISSED_EMPLOYEE
                    "VIOLATION_DISMISSED_EMPLOYEE",
                    "Vi phạm đã được bỏ qua",
                    "Gửi cho bạn khi HR/quản lý bỏ qua (không tính) một vi phạm đã ghi nhận trước đó.",
                    true,
                    false,
                    "normal")
    );

    private static final Map<String, String> PRIORITY_BY_EVENT_TYPE = ALL.stream()
            .collect(java.util.stream.Collectors.toMap(
                    NotificationEventTypeInfo::eventType, NotificationEventTypeInfo::defaultPriority));

    /** #89 (2026-08-17): resolves the default priority for an eventType — used by
     *  {@code NotificationService#createNotification} at creation time so each notification's
     *  priority is a real backend decision, not a FE-hardcoded color per eventType. Falls back
     *  to {@link #FALLBACK_PRIORITY} for an eventType this catalog doesn't (yet) know about,
     *  rather than throwing — a send should never fail just because priority lookup missed. */
    public static String defaultPriorityFor(String eventType) {
        return PRIORITY_BY_EVENT_TYPE.getOrDefault(eventType, FALLBACK_PRIORITY);
    }

    public record NotificationEventTypeInfo(
            String eventType,
            String label,
            String description,
            boolean defaultInAppEnabled,
            boolean defaultPushEnabled,
            String defaultPriority) {
    }
}
