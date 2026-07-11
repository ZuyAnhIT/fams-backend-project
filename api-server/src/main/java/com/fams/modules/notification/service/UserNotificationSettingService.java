package com.fams.modules.notification.service;

import com.fams.modules.notification.dto.request.UpsertNotificationSettingRequest;
import com.fams.modules.notification.dto.response.UserNotificationSettingResponse;
import com.fams.modules.notification.entity.UserNotificationSetting;
import com.fams.modules.notification.repository.UserNotificationSettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
     * Returns all notification settings for the given user.
     *
     * @param userId user UUID from JWT
     * @return list of settings (may be empty for a new user)
     */
    @Transactional(readOnly = true)
    public List<UserNotificationSettingResponse> getSettings(UUID userId) {
        log.debug("Getting notification settings for userId={}", userId);
        return settingRepository.findAllByUserId(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
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
        return UserNotificationSettingResponse.builder()
                .id(s.getId())
                .userId(s.getUserId())
                .eventType(s.getEventType())
                .inAppEnabled(s.isInAppEnabled())
                .pushEnabled(s.isPushEnabled())
                .updatedAt(s.getUpdatedAt())
                .build();
    }
}
