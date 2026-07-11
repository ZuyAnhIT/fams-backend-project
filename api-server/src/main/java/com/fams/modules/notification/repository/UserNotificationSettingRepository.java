package com.fams.modules.notification.repository;

import com.fams.modules.notification.entity.UserNotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserNotificationSettingRepository extends JpaRepository<UserNotificationSetting, UUID> {

    Optional<UserNotificationSetting> findByUserIdAndEventType(UUID userId, String eventType);

    List<UserNotificationSetting> findAllByUserId(UUID userId);
}
