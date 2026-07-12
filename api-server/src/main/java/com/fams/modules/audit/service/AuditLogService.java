package com.fams.modules.audit.service;

import com.fams.modules.audit.dto.response.AuditLogResponse;
import com.fams.modules.audit.entity.AuditLog;
import com.fams.modules.audit.repository.AuditLogRepository;
import com.fams.modules.audit.repository.AuditLogSpecification;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.util.MaskingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AuditLogService {

  private final AuditLogRepository auditLogRepository;

  public AuditLogService(AuditLogRepository auditLogRepository) {
    this.auditLogRepository = auditLogRepository;
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
      int size) {
    Page<AuditLog> result =
        auditLogRepository.findAll(
            AuditLogSpecification.withFilters(tenantId, actorId, entityType, entityId, action, from, to),
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    return PageResponse.from(result.map(this::toResponse));
  }

  @Transactional(readOnly = true)
  public AuditLogResponse getById(UUID id) {
    AuditLog log = auditLogRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Audit log not found: " + id));
    return toResponse(log);
  }

  @Transactional(readOnly = true)
  public List<AuditLogResponse> findByRequestId(String requestId) {
    return auditLogRepository.findByRequestId(requestId).stream()
        .map(this::toResponse)
        .collect(Collectors.toList());
  }

  @Transactional
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
        .build();
    AuditLog saved = auditLogRepository.save(entry);
    log.debug("Audit recorded action={} entity={}/{} actor={}", action, entityType, entityId, actorEmail);
    return toResponse(saved);
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
        .createdAt(a.getCreatedAt())
        .build();
  }
}
