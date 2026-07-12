package com.fams.modules.notification.repository;

import com.fams.modules.notification.entity.NotificationDeliveryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface NotificationDeliveryLogRepository extends JpaRepository<NotificationDeliveryLog, UUID> {

  List<NotificationDeliveryLog> findByNotificationIdOrderByCreatedAtAsc(UUID notificationId);

  @Modifying
  @Query("DELETE FROM NotificationDeliveryLog l WHERE l.createdAt < :cutoff")
  int deleteByCreatedAtBefore(@Param("cutoff") OffsetDateTime cutoff);
}
