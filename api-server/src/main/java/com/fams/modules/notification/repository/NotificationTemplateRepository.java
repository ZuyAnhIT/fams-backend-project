package com.fams.modules.notification.repository;

import com.fams.modules.notification.entity.NotificationTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

  /** Find a template by tenant, event type, and locale, excluding soft-deleted. */
  @Query(
      "SELECT t FROM NotificationTemplate t "
          + "WHERE t.tenantId = :tenantId AND t.eventType = :eventType "
          + "AND t.locale = :locale AND t.deletedAt IS NULL")
  Optional<NotificationTemplate> findByTenantIdAndEventTypeAndLocaleAndDeletedAtIsNull(
      @Param("tenantId") UUID tenantId,
      @Param("eventType") String eventType,
      @Param("locale") String locale);

  /** Paginated list of templates for a tenant, excluding soft-deleted, newest first. */
  @Query(
      "SELECT t FROM NotificationTemplate t "
          + "WHERE t.tenantId = :tenantId AND t.deletedAt IS NULL "
          + "ORDER BY t.createdAt DESC")
  Page<NotificationTemplate> findAllByTenantIdAndDeletedAtIsNull(
      @Param("tenantId") UUID tenantId, Pageable pageable);

  /** Find a single template by id and tenant, excluding soft-deleted. */
  @Query(
      "SELECT t FROM NotificationTemplate t "
          + "WHERE t.id = :id AND t.tenantId = :tenantId AND t.deletedAt IS NULL")
  Optional<NotificationTemplate> findByIdAndTenantIdAndDeletedAtIsNull(
      @Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
