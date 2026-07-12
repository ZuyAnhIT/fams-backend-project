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

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) createdAt = OffsetDateTime.now();
  }
}
