package com.fams.modules.notification.repository;

import com.fams.modules.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  /** Paginated notifications for a user in a tenant, excluding soft-deleted, newest first. */
  @Query(
      "SELECT n FROM Notification n "
          + "WHERE n.tenantId = :tenantId AND n.userId = :userId AND n.deletedAt IS NULL "
          + "ORDER BY n.createdAt DESC")
  Page<Notification> findByTenantAndUser(
      @Param("tenantId") UUID tenantId, @Param("userId") UUID userId, Pageable pageable);

  /** Paginated unread-only notifications for a user in a tenant. */
  @Query(
      "SELECT n FROM Notification n "
          + "WHERE n.tenantId = :tenantId AND n.userId = :userId "
          + "AND n.isRead = false AND n.deletedAt IS NULL "
          + "ORDER BY n.createdAt DESC")
  Page<Notification> findUnreadByTenantAndUser(
      @Param("tenantId") UUID tenantId, @Param("userId") UUID userId, Pageable pageable);

  /** Count of unread notifications for a user in a tenant. */
  @Query(
      "SELECT COUNT(n) FROM Notification n "
          + "WHERE n.tenantId = :tenantId AND n.userId = :userId "
          + "AND n.isRead = false AND n.deletedAt IS NULL")
  long countUnreadByTenantAndUser(
      @Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

  /** Find a single notification scoped to tenant and user (excludes soft-deleted). */
  @Query(
      "SELECT n FROM Notification n "
          + "WHERE n.id = :id AND n.tenantId = :tenantId AND n.userId = :userId "
          + "AND n.deletedAt IS NULL")
  Optional<Notification> findByIdAndTenantIdAndUserId(
      @Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

  /** Bulk-mark all unread notifications as read for a user in a tenant. Returns row count. */
  @Modifying
  @Query(
      "UPDATE Notification n SET n.isRead = true, n.readAt = CURRENT_TIMESTAMP "
          + "WHERE n.tenantId = :tenantId AND n.userId = :userId "
          + "AND n.isRead = false AND n.deletedAt IS NULL")
  int markAllAsRead(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

  @Modifying
  @Query("DELETE FROM Notification n WHERE n.createdAt < :cutoff AND n.isRead = true")
  int deleteReadNotificationsOlderThan(@Param("cutoff") OffsetDateTime cutoff);

  /** Bulk-mark a specific set of notifications as read (audit 2026-08-05 — previously the only
   *  choices were exactly one, via markAsRead, or literally all via markAllAsRead; nothing for a
   *  user multi-selecting a subset of their inbox, the common case in Gmail/Slack-style UIs).
   *  Scoped to tenant+user like every other notification query — a caller can never mark another
   *  user's notification as read even by guessing its ID. Returns row count. */
  @Modifying
  @Query(
      "UPDATE Notification n SET n.isRead = true, n.readAt = CURRENT_TIMESTAMP "
          + "WHERE n.tenantId = :tenantId AND n.userId = :userId "
          + "AND n.id IN :ids AND n.isRead = false AND n.deletedAt IS NULL")
  int markAsReadBatch(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
                       @Param("ids") Collection<UUID> ids);
}
