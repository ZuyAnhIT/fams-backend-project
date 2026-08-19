package com.fams.modules.notification.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notification_delivery_logs")
public class NotificationDeliveryLog {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(name = "notification_id")
  private UUID notificationId;

  /** #144 (2026-08-19 follow-up): nullable — a platform-admin ops alert (ScheduledJobMonitor) or
   *  any row with no notificationId link genuinely has no tenant. */
  @Column(name = "tenant_id")
  private UUID tenantId;

  @Column(name = "device_token", length = 512)
  private String deviceToken;

  @Column(name = "channel", nullable = false, length = 50)
  private String channel;

  @Column(name = "attempt_number", nullable = false)
  private int attemptNumber;

  /** SUCCESS, FAILED, FALLBACK_EMAIL_SENT, FALLBACK_EMAIL_FAILED */
  @Column(name = "status", nullable = false, length = 50)
  private String status;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  /** #88 (2026-08-17): FCM's messageId on a successful send — null for failed attempts and for
   *  the email-fallback channel (no equivalent concept there). */
  @Column(name = "provider_message_id")
  private String providerMessageId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) createdAt = OffsetDateTime.now();
  }
}
