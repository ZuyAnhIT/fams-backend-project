package com.fams.modules.audit.entity;

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
@Table(name = "audit_logs")
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(name = "tenant_id")
  private UUID tenantId;

  @Column(name = "actor_id")
  private UUID actorId;

  @Column(name = "actor_email", length = 255)
  private String actorEmail;

  @Column(name = "entity_type", nullable = false, length = 100)
  private String entityType;

  @Column(name = "entity_id", length = 255)
  private String entityId;

  @Column(name = "action", nullable = false, length = 100)
  private String action;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "old_value", columnDefinition = "jsonb")
  private Map<String, Object> oldValue;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "new_value", columnDefinition = "jsonb")
  private Map<String, Object> newValue;

  @Column(name = "request_id", length = 100)
  private String requestId;

  @Column(name = "ip_address", length = 64)
  private String ipAddress;

  @Column(name = "user_agent")
  private String userAgent;

  /** #138 (2026-08-19 follow-up): captured synchronously in AuditLogService#record — the
   *  request path is known at record time, unlike httpStatus below. */
  @Column(name = "endpoint", length = 500)
  private String endpoint;

  /** #138 (2026-08-19 follow-up): NOT known when record() runs (the controller hasn't returned
   *  yet) — backfilled best-effort by RequestIdFilter after the response completes, correlated
   *  by requestId. Null if the backfill never ran or failed; never blocks the real response. */
  @Column(name = "http_status")
  private Integer httpStatus;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
  }
}
