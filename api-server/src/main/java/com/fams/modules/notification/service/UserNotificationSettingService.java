package com.fams.modules.notification.service;

import com.fams.modules.notification.constant.NotificationEventTypeCatalog;
import com.fams.modules.notification.constant.NotificationEventTypeCatalog.NotificationEventTypeInfo;
import com.fams.modules.notification.dto.request.UpsertNotificationSettingRequest;
import com.fams.modules.notification.dto.response.UserNotificationSettingResponse;
import com.fams.modules.notification.entity.UserNotificationSetting;
import com.fams.modules.notification.repository.UserNotificationSettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserNotificationSettingService {

    private final UserNotificationSettingRepository settingRepository;

    public UserNotificationSettingService(UserNotificationSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    /**
     * Returns effective notification settings for the UNION of every catalog event type and
     * every event type the user has actually saved a row for — not just the intersection either
     * way. Two audit-2026-08-05 gaps, fixed together:
     * <ul>
     *   <li>Previously only ever returned saved rows — a user who never touched settings got an
     *       empty list, with no way to discover what event types even exist.</li>
     *   <li>A naive "just return the catalog" fix (the first attempt at this fix) broke the
     *       opposite case: {@code NotificationTemplate.eventType} is deliberately free text per
     *       tenant (a tenant can define its own custom event types beyond the system catalog —
     *       see {@code NotificationTemplateController}), and a user could already have saved a
     *       real customization for one. Filtering the response down to only catalog entries would
     *       have silently hidden that saved customization from the user's own settings screen,
     *       even though {@link #isInAppEnabled}/{@link #isPushEnabled} still honor it correctly.</li>
     * </ul>
     * An event type the user hasn't customized comes back with the catalog's default flags and
     * {@code customized=false}; one they have saved a row for (catalog or tenant-custom) comes
     * back with their actual choice, {@code customized=true}, and a {@code label} only if it's a
     * known catalog type (null for a tenant-custom one — FE falls back to showing the raw code).
     *
     * @param userId user UUID from JWT
     * @return one row per catalog event type, plus one per any additional tenant-custom event
     *         type the user has saved a row for (never empty — catalog alone guarantees ≥1)
     */
    @Transactional(readOnly = true)
    public List<UserNotificationSettingResponse> getSettings(UUID userId) {
        log.debug("Getting notification settings for userId={}", userId);
        Map<String, UserNotificationSetting> saved = settingRepository.findAllByUserId(userId)
                .stream()
                .collect(Collectors.toMap(UserNotificationSetting::getEventType, s -> s));

        List<UserNotificationSettingResponse> result = NotificationEventTypeCatalog.ALL.stream()
                .map(info -> {
                    UserNotificationSetting existing = saved.remove(info.eventType());
                    return existing != null ? toResponse(existing, info) : toDefaultResponse(userId, info);
                })
                .collect(Collectors.toCollection(java.util.ArrayList::new));

        // Whatever's left in `saved` is a tenant-custom event type not in the system catalog —
        // still a real, previously-visible customization, append it as-is (no catalog label).
        saved.values().stream().map(this::toResponse).forEach(result::add);

        return result;
    }

    /**
     * Upserts a single notification setting for the given user and event type.
     *
     * @param userId        user UUID from JWT
     * @param eventType     event type string
     * @param inAppEnabled  whether in-app notifications are enabled
     * @param pushEnabled   whether push notifications are enabled
     * @return the created or updated setting
     */
    @Transactional
    public UserNotificationSettingResponse upsertSetting(
            UUID userId, String eventType, boolean inAppEnabled, boolean pushEnabled) {
        log.info("Upserting notification setting userId={} eventType={} inApp={} push={}",
                userId, eventType, inAppEnabled, pushEnabled);

        assertNotDisablingMandatoryNotification(eventType, inAppEnabled, pushEnabled);

        UserNotificationSetting setting = settingRepository
                .findByUserIdAndEventType(userId, eventType)
                .orElseGet(() -> UserNotificationSetting.builder()
                        .userId(userId)
                        .eventType(eventType)
                        .build());

        setting.setInAppEnabled(inAppEnabled);
        setting.setPushEnabled(pushEnabled);

        UserNotificationSetting saved = settingRepository.save(setting);
        log.debug("Notification setting saved id={}", saved.getId());
        return toResponse(saved);
    }

    /**
     * #141 (2026-08-19): AC requires "không cho tắt thông báo bắt buộc như random_check_request"
     * — genuinely missing before, a user could freely disable in-app/push for ANY eventType
     * including RANDOM_CHECK_SENT (now the system's only "critical" priority eventType, see
     * NotificationEventTypeCatalog — bumped from "high" as part of #140 the same day, specifically
     * because it's the one mandatory, time-boxed notification: an employee must respond within a
     * short window or it becomes a violation). Gating on priority=="critical" rather than
     * hardcoding "RANDOM_CHECK_SENT" so any future mandatory eventType is covered automatically
     * without another code change here.
     */
    private void assertNotDisablingMandatoryNotification(String eventType, boolean inAppEnabled, boolean pushEnabled) {
        if (inAppEnabled && pushEnabled) return;
        if (!"critical".equals(NotificationEventTypeCatalog.defaultPriorityFor(eventType))) return;
        throw new com.fams.shared.exception.BusinessException(
                "MANDATORY_NOTIFICATION",
                "Thông báo này là bắt buộc và không thể tắt.",
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "Cannot disable mandatory notification eventType=" + eventType);
    }

    /**
     * Bulk upserts notification settings for the given user.
     *
     * @param userId   user UUID from JWT
     * @param settings list of settings to upsert
     * @return list of created or updated settings
     */
    @Transactional
    public List<UserNotificationSettingResponse> upsertSettings(
            UUID userId, List<UpsertNotificationSettingRequest> settings) {
        log.info("Bulk upserting {} notification settings for userId={}", settings.size(), userId);
        return settings.stream()
                .map(req -> upsertSetting(
                        userId,
                        req.getEventType(),
                        req.getInAppEnabled() != null ? req.getInAppEnabled() : true,
                        req.getPushEnabled() != null ? req.getPushEnabled() : true))
                .collect(Collectors.toList());
    }

    /**
     * Returns whether in-app notifications are enabled for the given user and event type.
     * Defaults to true if no setting exists (default opt-in).
     *
     * @param userId    user UUID
     * @param eventType event type string
     * @return true if enabled (or no setting row exists), false if explicitly disabled
     */
    @Transactional(readOnly = true)
    public boolean isInAppEnabled(UUID userId, String eventType) {
        return settingRepository
                .findByUserIdAndEventType(userId, eventType)
                .map(UserNotificationSetting::isInAppEnabled)
                .orElse(true);
    }

    /**
     * Returns whether push notifications are enabled for the given user and event type.
     * Defaults to true if no setting exists (default opt-in).
     *
     * @param userId    user UUID
     * @param eventType event type string
     * @return true if enabled (or no setting row exists), false if explicitly disabled
     */
    @Transactional(readOnly = true)
    public boolean isPushEnabled(UUID userId, String eventType) {
        return settingRepository
                .findByUserIdAndEventType(userId, eventType)
                .map(UserNotificationSetting::isPushEnabled)
                .orElse(true);
    }

    private UserNotificationSettingResponse toResponse(UserNotificationSetting s) {
        NotificationEventTypeInfo info = NotificationEventTypeCatalog.ALL.stream()
                .filter(i -> i.eventType().equals(s.getEventType()))
                .findFirst()
                .orElse(null);
        return toResponse(s, info);
    }

    private UserNotificationSettingResponse toResponse(UserNotificationSetting s, NotificationEventTypeInfo info) {
        return UserNotificationSettingResponse.builder()
                .id(s.getId())
                .userId(s.getUserId())
                .eventType(s.getEventType())
                .label(info != null ? info.label() : null)
                .inAppEnabled(s.isInAppEnabled())
                .pushEnabled(s.isPushEnabled())
                .customized(true)
                .mandatory("critical".equals(NotificationEventTypeCatalog.defaultPriorityFor(s.getEventType())))
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    /** A catalog event type the user has never explicitly saved a row for — returns the
     *  catalog's own default flags, {@code customized=false}, and no {@code id}/{@code updatedAt}
     *  (nothing has actually been persisted). */
    private UserNotificationSettingResponse toDefaultResponse(UUID userId, NotificationEventTypeInfo info) {
        return UserNotificationSettingResponse.builder()
                .id(null)
                .userId(userId)
                .eventType(info.eventType())
                .label(info.label())
                .inAppEnabled(info.defaultInAppEnabled())
                .pushEnabled(info.defaultPushEnabled())
                .customized(false)
                .mandatory("critical".equals(info.defaultPriority()))
                .updatedAt(null)
                .build();
    }
}
