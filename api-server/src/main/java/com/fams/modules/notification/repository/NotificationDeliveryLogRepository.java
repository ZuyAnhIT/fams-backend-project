package com.fams.modules.notification.repository;

import com.fams.modules.notification.entity.NotificationDeliveryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface NotificationDeliveryLogRepository
    extends JpaRepository<NotificationDeliveryLog, UUID>, JpaSpecificationExecutor<NotificationDeliveryLog> {

  List<NotificationDeliveryLog> findByNotificationIdOrderByCreatedAtAsc(UUID notificationId);

  /** Rows with no tenantId (null — e.g. platform-admin ops alerts, or a row whose
   *  notificationId link predates migration V109's backfill) can never be reached by the
   *  tenant-scoped variant below, so this global sweep stays necessary alongside it, not just a
   *  historical leftover. */
  @Modifying
  @Query("DELETE FROM NotificationDeliveryLog l WHERE l.createdAt < :cutoff AND l.tenantId IS NULL")
  int deleteByCreatedAtBefore(@Param("cutoff") OffsetDateTime cutoff);

  /** #144 (2026-08-19 follow-up): tenant-scoped sweep, now possible since migration V109 added
   *  tenant_id. DataRetentionJob calls this per-tenant with each tenant's own effective
   *  retention window, same as it already does for notifications/biometric photos. */
  @Modifying
  @Query("DELETE FROM NotificationDeliveryLog l WHERE l.tenantId = :tenantId AND l.createdAt < :cutoff")
  int deleteByTenantIdAndCreatedAtBefore(@Param("tenantId") UUID tenantId, @Param("cutoff") OffsetDateTime cutoff);
}
