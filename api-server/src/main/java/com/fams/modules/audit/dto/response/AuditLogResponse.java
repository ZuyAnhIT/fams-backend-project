package com.fams.modules.audit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@Schema(description = "A single audit log entry")
public class AuditLogResponse {

  @Schema(description = "Audit log UUID")
  private UUID id;

  @Schema(description = "Tenant UUID (null for platform-level actions)")
  private UUID tenantId;

  @Schema(description = "User UUID of the actor")
  private UUID actorId;

  @Schema(description = "Email of the actor at time of action", example = "admin@acme.com")
  private String actorEmail;

  @Schema(description = "Display name of the actor, resolved from actorId at read time — null if "
      + "the actor account was deleted or the action had no actor (system job). (#audit-readability)",
      example = "Nguyễn Văn An")
  private String actorName;

  @Schema(description = "Type of entity affected", example = "Employee")
  private String entityType;

  @Schema(description = "ID of the affected entity", example = "550e8400-e29b-41d4-a716-446655440000")
  private String entityId;

  @Schema(description = "Human-readable name of the affected entity, resolved at read time (employee "
      + "name, site name, …) — null when the entity can't be named (e.g. AccessControl rows) or was "
      + "deleted. (#audit-readability)", example = "Công trình Quận 1")
  private String entityName;

  @Schema(description = "Action performed", example = "UPDATE")
  private String action;

  @Schema(description = "State of entity before the action (null for CREATE)")
  private Map<String, Object> oldValue;

  @Schema(description = "State of entity after the action (null for DELETE)")
  private Map<String, Object> newValue;

  @Schema(description = "Correlation request ID", example = "req-abc123")
  private String requestId;

  @Schema(description = "IP address of the actor")
  private String ipAddress;

  @Schema(description = "User-Agent of the actor's client")
  private String userAgent;

  @Schema(description = "Request path this action occurred in, e.g. /api/v1/tenants/{id} (#138, 2026-08-19)", example = "/api/v1/tenants/550e8400-e29b-41d4-a716-446655440000")
  private String endpoint;

  @Schema(description = "Final HTTP status of the request this action occurred in — null if the "
      + "backfill hasn't run yet or the action wasn't written inside a real HTTP request (e.g. a "
      + "scheduled job) (#138, 2026-08-19)", example = "200")
  private Integer httpStatus;

  @Schema(description = "Timestamp of the action")
  private OffsetDateTime createdAt;
}
