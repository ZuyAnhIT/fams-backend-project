package com.fams.modules.audit.service;

import com.fams.modules.audit.dto.response.AuditLogResponse;
import com.fams.modules.audit.entity.AuditLog;
import com.fams.modules.audit.repository.AuditLogRepository;
import com.fams.modules.audit.repository.AuditLogSpecification;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.security.HttpRequestUtils;
import com.fams.shared.util.MaskingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AuditLogService {

  private final AuditLogRepository auditLogRepository;
  private final UserRoleRepository userRoleRepository;

  public AuditLogService(AuditLogRepository auditLogRepository, UserRoleRepository userRoleRepository) {
    this.auditLogRepository = auditLogRepository;
    this.userRoleRepository = userRoleRepository;
  }

  /**
   * Resolves which tenant(s) a non-platform-admin caller is even allowed to see audit logs for,
   * from their own active role assignments — audit 2026-08-06: {@code GET /api/v1/audit-logs}
   * was previously wide open to cross-tenant reads. The endpoint takes {@code tenantId} as a
   * plain optional query filter with no relation at all to who's calling — a TENANT_ADMIN or
   * HR_MANAGER (both routinely granted {@code audit:list}) could omit it to see every tenant's
   * actor emails/IP addresses/old-new values platform-wide, or pass any other tenant's UUID
   * directly. Platform Admin is intentionally exempt (their whole job is cross-tenant support).
   */
  private Set<UUID> resolveCallerTenants(UUID callerUserId) {
    return userRoleRepository.findAllActiveByUserId(callerUserId).stream()
        .map(UserRole::getTenantId)
        .filter(t -> t != null) // platform-level role rows (tenantId=null) don't grant any tenant's audit view
        .collect(Collectors.toSet());
  }

  /**
   * @param requestedTenantId the {@code tenantId} query param as sent, may be null
   * @return the tenantId to actually filter by; null only ever means "every tenant", which is
   *         only returned for a platform admin
   */
  private UUID enforceScope(UUID callerUserId, boolean callerIsPlatformAdmin, UUID requestedTenantId) {
    if (callerIsPlatformAdmin) {
      return requestedTenantId;
    }
    Set<UUID> allowed = resolveCallerTenants(callerUserId);
    if (requestedTenantId != null) {
      if (!allowed.contains(requestedTenantId)) {
        throw new AccessDeniedException("You do not have permission to view audit logs for this tenant");
      }
      return requestedTenantId;
    }
    if (allowed.size() == 1) {
      return allowed.iterator().next();
    }
    if (allowed.isEmpty()) {
      // No active tenant role at all (shouldn't normally happen for someone holding
      // audit:list) — fail closed to a tenant no data can ever match, not "no filter".
      return UUID.randomUUID();
    }
    throw new AccessDeniedException(
        "You are scoped to multiple tenants — pass a specific tenantId to view its audit logs");
  }

  @Transactional(readOnly = true)
  public PageResponse<AuditLogResponse> listAuditLogs(
      UUID tenantId,
      UUID actorId,
      String entityType,
      String entityId,
      String action,
      OffsetDateTime from,
      OffsetDateTime to,
      int page,
      int size,
      UUID callerUserId,
      boolean callerIsPlatformAdmin) {
    UUID effectiveTenantId = enforceScope(callerUserId, callerIsPlatformAdmin, tenantId);
    Page<AuditLog> result =
        auditLogRepository.findAll(
            AuditLogSpecification.withFilters(effectiveTenantId, actorId, entityType, entityId, action, from, to),
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    return PageResponse.from(result.map(this::toResponse));
  }

  @Transactional(readOnly = true)
  public AuditLogResponse getById(UUID id, UUID callerUserId, boolean callerIsPlatformAdmin) {
    AuditLog log = auditLogRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Audit log not found: " + id));
    if (!callerIsPlatformAdmin) {
      Set<UUID> allowed = resolveCallerTenants(callerUserId);
      // 404, not 403 — matches the "don't confirm existence of data outside your scope"
      // convention already used elsewhere in this codebase (e.g. notification lookups).
      if (log.getTenantId() == null || !allowed.contains(log.getTenantId())) {
        throw new ResourceNotFoundException("Audit log not found: " + id);
      }
    }
    return toResponse(log);
  }

  @Transactional(readOnly = true)
  public List<AuditLogResponse> findByRequestId(String requestId, UUID callerUserId, boolean callerIsPlatformAdmin) {
    List<AuditLog> entries = auditLogRepository.findByRequestId(requestId);
    if (!callerIsPlatformAdmin) {
      Set<UUID> allowed = resolveCallerTenants(callerUserId);
      entries = entries.stream()
          .filter(e -> e.getTenantId() != null && allowed.contains(e.getTenantId()))
          .collect(Collectors.toList());
    }
    return entries.stream().map(this::toResponse).collect(Collectors.toList());
  }

  /** REQUIRES_NEW (found via audit, 2026-08-18): callers occasionally run in a read-only
   *  transaction (e.g. ReportService#exportMonthlyAttendance) — under the default REQUIRED
   *  propagation this insert would join that transaction, get silently dropped by Hibernate's
   *  read-only flush suppression, and commit as a no-op with no exception and no row written.
   *  Audit records must not depend on the caller's transaction semantics anyway. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AuditLogResponse record(
      UUID tenantId,
      UUID actorId,
      String actorEmail,
      String entityType,
      String entityId,
      String action,
      Map<String, Object> oldValue,
      Map<String, Object> newValue,
      String requestId,
      String ipAddress,
      String userAgent) {
    AuditLog entry = AuditLog.builder()
        .tenantId(tenantId)
        .actorId(actorId)
        .actorEmail(actorEmail)
        .entityType(entityType)
        .entityId(entityId)
        .action(action)
        .oldValue(MaskingUtils.maskAuditMap(oldValue))
        .newValue(MaskingUtils.maskAuditMap(newValue))
        .requestId(requestId)
        .ipAddress(ipAddress)
        .userAgent(userAgent)
        .endpoint(HttpRequestUtils.currentRequestPath())
        .build();
    AuditLog saved = auditLogRepository.save(entry);
    HttpRequestUtils.markAuditWritten();
    log.debug("Audit recorded action={} entity={}/{} actor={}", action, entityType, entityId, actorEmail);
    return toResponse(saved);
  }

  /** #138 (2026-08-19 follow-up): called by RequestIdFilter after the response completes, once
   *  per request — REQUIRES_NEW same as record() so it never depends on the (already-finished)
   *  request's transaction. Best-effort by design: the caller wraps this in try/catch and never
   *  lets a failure here affect the real response already sent to the client. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void backfillHttpStatus(String requestId, int httpStatus) {
    auditLogRepository.backfillHttpStatusByRequestId(requestId, httpStatus);
  }

  private AuditLogResponse toResponse(AuditLog a) {
    return AuditLogResponse.builder()
        .id(a.getId())
        .tenantId(a.getTenantId())
        .actorId(a.getActorId())
        .actorEmail(a.getActorEmail())
        .entityType(a.getEntityType())
        .entityId(a.getEntityId())
        .action(a.getAction())
        .oldValue(a.getOldValue())
        .newValue(a.getNewValue())
        .requestId(a.getRequestId())
        .ipAddress(a.getIpAddress())
        .userAgent(a.getUserAgent())
        .endpoint(a.getEndpoint())
        .httpStatus(a.getHttpStatus())
        .createdAt(a.getCreatedAt())
        .build();
  }
}
