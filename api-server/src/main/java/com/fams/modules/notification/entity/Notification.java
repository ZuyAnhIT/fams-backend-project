package com.fams.modules.notification.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notifications")
public class Notification {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "event_type", nullable = false, length = 100)
  private String eventType;

  @Column(nullable = false, length = 255)
  private String title;

  @Column(columnDefinition = "TEXT")
  private String body;

  /** Structured, machine-readable payload for deep-linking (e.g. {"checkId": "...", "siteId": "...",
   *  "expiresAt": "..."} for RANDOM_CHECK_SENT) — null for notification types that don't need it. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> metadata;

  /** low | normal | high | critical — #89 (2026-08-17), resolved from
   *  {@link com.fams.modules.notification.constant.NotificationEventTypeCatalog} at creation
   *  time by eventType, NOT re-derived later (same snapshot principle as other event-driven
   *  fields in this codebase — a future catalog edit must never retroactively change an
   *  already-sent notification's priority). */
  @Column(nullable = false, length = 20)
  private String priority;

  @Column(name = "is_read", nullable = false)
  private boolean isRead;

  @Column(name = "read_at")
  private OffsetDateTime readAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "deleted_at")
  private OffsetDateTime deletedAt;

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
  }
}
