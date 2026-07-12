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

  @Schema(description = "Type of entity affected", example = "Employee")
  private String entityType;

  @Schema(description = "ID of the affected entity", example = "550e8400-e29b-41d4-a716-446655440000")
  private String entityId;

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

  @Schema(description = "Timestamp of the action")
  private OffsetDateTime createdAt;
}
