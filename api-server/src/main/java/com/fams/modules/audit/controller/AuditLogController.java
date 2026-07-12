package com.fams.modules.audit.controller;

import com.fams.modules.audit.dto.response.AuditLogResponse;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "Audit", description = "Audit log viewer — PLATFORM_ADMIN and TENANT_ADMIN only")
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

  private final AuditLogService auditLogService;

  public AuditLogController(AuditLogService auditLogService) {
    this.auditLogService = auditLogService;
  }

  @Operation(
      summary = "List audit logs",
      description =
          "Returns a paginated list of audit log entries. Supports filtering by actor, entity type, "
              + "entity ID, action, date range, and request ID. "
              + "Accessible by PLATFORM_ADMIN or users with audit:list permission.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Paginated audit log list",
        content = @Content(schema = @Schema(implementation = PageResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "Unauthorized — valid JWT required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "Forbidden — insufficient permissions")
  })
  @GetMapping
  @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('audit:list')")
  public ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> listAuditLogs(
      @Parameter(description = "Filter by tenant UUID") @RequestParam(required = false) UUID tenantId,
      @Parameter(description = "Filter by actor user UUID") @RequestParam(required = false) UUID actorId,
      @Parameter(description = "Filter by entity type (e.g. Employee, Site)") @RequestParam(required = false) String entityType,
      @Parameter(description = "Filter by entity ID") @RequestParam(required = false) String entityId,
      @Parameter(description = "Filter by action (e.g. CREATE, UPDATE, DELETE)") @RequestParam(required = false) String action,
      @Parameter(description = "Filter by request correlation ID") @RequestParam(required = false) String requestId,
      @Parameter(description = "Start of date range (ISO-8601)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
      @Parameter(description = "End of date range (ISO-8601)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
      @Parameter(description = "Zero-based page index (default 0)") @RequestParam(defaultValue = "0") int page,
      @Parameter(description = "Page size (default 20, max 200)") @RequestParam(defaultValue = "20") int size) {

    size = Math.min(size, 200);

    if (requestId != null && !requestId.isBlank()) {
      log.info("Audit trace by requestId={}", requestId);
      List<AuditLogResponse> entries = auditLogService.findByRequestId(requestId);
      PageResponse<AuditLogResponse> singlePage = PageResponse.<AuditLogResponse>builder()
          .content(entries)
          .page(0)
          .size(entries.size())
          .totalElements(entries.size())
          .totalPages(1)
          .first(true)
          .last(true)
          .build();
      return ResponseEntity.ok(ApiResponse.success(singlePage));
    }

    log.info(
        "List audit logs tenantId={} actorId={} entityType={} entityId={} action={} page={} size={}",
        tenantId, actorId, entityType, entityId, action, page, size);
    PageResponse<AuditLogResponse> result =
        auditLogService.listAuditLogs(tenantId, actorId, entityType, entityId, action, from, to, page, size);
    return ResponseEntity.ok(ApiResponse.success(result));
  }

  @Operation(
      summary = "Get audit log entry",
      description =
          "Returns a single audit log entry including old_value and new_value JSONB diffs. "
              + "Accessible by PLATFORM_ADMIN or users with audit:read permission.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Audit log entry",
        content = @Content(schema = @Schema(implementation = AuditLogResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "Unauthorized — valid JWT required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "Forbidden — insufficient permissions"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Audit log entry not found")
  })
  @GetMapping("/{id}")
  @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('audit:read')")
  public ResponseEntity<ApiResponse<AuditLogResponse>> getAuditLog(
      @Parameter(description = "Audit log entry UUID") @PathVariable UUID id) {
    log.info("Get audit log id={}", id);
    AuditLogResponse response = auditLogService.getById(id);
    return ResponseEntity.ok(ApiResponse.success(response));
  }
}
