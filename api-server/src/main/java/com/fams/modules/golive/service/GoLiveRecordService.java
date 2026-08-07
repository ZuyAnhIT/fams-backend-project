package com.fams.modules.golive.service;

import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.golive.dto.request.ApproveGoLiveRecordRequest;
import com.fams.modules.golive.dto.request.CreateGoLiveRecordRequest;
import com.fams.modules.golive.dto.request.GoLiveStepInput;
import com.fams.modules.golive.dto.request.UpdateGoLiveStepsRequest;
import com.fams.modules.golive.dto.response.GoLiveRecordResponse;
import com.fams.modules.golive.entity.GoLiveRecord;
import com.fams.modules.golive.repository.GoLiveRecordRepository;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Formal go-live sign-off records — one row per go-live attempt for a tenant, persisting exactly
 * what FE asked for (2026-08-06): tenant, environment, build version, tester, timestamps, each
 * step's result + evidence, and the approver. Platform-level (cross-tenant by nature — a
 * deployment team member isn't necessarily a member of the tenant going live), so gated by
 * PLATFORM_ADMIN / golive:manage only, matching every other /api/v1/platform/... controller.
 */
@Slf4j
@Service
public class GoLiveRecordService {

    private final GoLiveRecordRepository repository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;

    public GoLiveRecordService(GoLiveRecordRepository repository, TenantRepository tenantRepository,
                                UserRepository userRepository) {
        this.repository = repository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public GoLiveRecordResponse create(CreateGoLiveRecordRequest request, UUID callerUserId) {
        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(request.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + request.getTenantId()));

        String performerName = userRepository.findByIdAndDeletedAtIsNull(callerUserId)
                .map(User::getDisplayName).orElse(null);

        GoLiveRecord record = GoLiveRecord.builder()
                .tenantId(tenant.getId())
                .environment(request.getEnvironment())
                .buildVersion(request.getBuildVersion())
                .status(GoLiveRecord.STATUS_DRAFT)
                .steps(toStepMaps(request.getSteps()))
                .performedBy(callerUserId)
                .performedByName(performerName)
                .startedAt(OffsetDateTime.now())
                .build();

        GoLiveRecord saved = repository.save(record);
        log.info("Go-live record created: id={} tenantId={} environment={} buildVersion={} by={}",
                saved.getId(), tenant.getId(), request.getEnvironment(), request.getBuildVersion(), callerUserId);
        return toResponse(saved, tenant.getName());
    }

    @Transactional
    public GoLiveRecordResponse updateSteps(UUID id, UpdateGoLiveStepsRequest request) {
        GoLiveRecord record = findOwned(id);
        if (!GoLiveRecord.STATUS_DRAFT.equals(record.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot edit steps on a record that has already been " + record.getStatus());
        }
        record.setSteps(toStepMaps(request.getSteps()));
        if (request.isCompleted() && record.getCompletedAt() == null) {
            record.setCompletedAt(OffsetDateTime.now());
        }
        GoLiveRecord saved = repository.save(record);
        log.info("Go-live record steps updated: id={}", id);
        return toResponse(saved, resolveTenantName(saved.getTenantId()));
    }

    @Transactional
    public GoLiveRecordResponse approve(UUID id, ApproveGoLiveRecordRequest request, UUID callerUserId) {
        return decide(id, GoLiveRecord.STATUS_APPROVED, request, callerUserId);
    }

    @Transactional
    public GoLiveRecordResponse reject(UUID id, ApproveGoLiveRecordRequest request, UUID callerUserId) {
        return decide(id, GoLiveRecord.STATUS_REJECTED, request, callerUserId);
    }

    private GoLiveRecordResponse decide(UUID id, String newStatus, ApproveGoLiveRecordRequest request, UUID callerUserId) {
        GoLiveRecord record = findOwned(id);
        if (!GoLiveRecord.STATUS_DRAFT.equals(record.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This record has already been " + record.getStatus());
        }
        String approverName = userRepository.findByIdAndDeletedAtIsNull(callerUserId)
                .map(User::getDisplayName).orElse(null);

        record.setStatus(newStatus);
        record.setApprovedBy(callerUserId);
        record.setApprovedByName(approverName);
        record.setApprovedAt(OffsetDateTime.now());
        record.setApprovalNote(request != null ? request.getNote() : null);

        GoLiveRecord saved = repository.save(record);
        log.info("Go-live record {}: id={} tenantId={} by={}", newStatus.toLowerCase(), id, saved.getTenantId(), callerUserId);
        return toResponse(saved, resolveTenantName(saved.getTenantId()));
    }

    @Transactional(readOnly = true)
    public GoLiveRecordResponse getById(UUID id) {
        GoLiveRecord record = findOwned(id);
        return toResponse(record, resolveTenantName(record.getTenantId()));
    }

    @Transactional(readOnly = true)
    public PageResponse<GoLiveRecordResponse> list(UUID tenantId, String status, Pageable pageable) {
        Page<GoLiveRecord> page;
        if (tenantId != null && status != null) {
            page = repository.findAllByDeletedAtIsNullAndTenantIdAndStatus(tenantId, status, pageable);
        } else if (tenantId != null) {
            page = repository.findAllByDeletedAtIsNullAndTenantId(tenantId, pageable);
        } else if (status != null) {
            page = repository.findAllByDeletedAtIsNullAndStatus(status, pageable);
        } else {
            page = repository.findAllByDeletedAtIsNull(pageable);
        }
        return PageResponse.from(page.map(r -> toResponse(r, resolveTenantName(r.getTenantId()))));
    }

    private GoLiveRecord findOwned(UUID id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Go-live record not found: " + id));
    }

    private String resolveTenantName(UUID tenantId) {
        return tenantRepository.findByIdAndDeletedAtIsNull(tenantId).map(Tenant::getName).orElse(null);
    }

    private List<Map<String, Object>> toStepMaps(List<GoLiveStepInput> steps) {
        if (steps == null) return List.of();
        return steps.stream().map(s -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("stepName", s.getStepName());
            m.put("result", s.getResult());
            m.put("note", s.getNote());
            m.put("evidenceUrl", s.getEvidenceUrl());
            return m;
        }).collect(Collectors.toList());
    }

    private GoLiveRecordResponse toResponse(GoLiveRecord r, String tenantName) {
        return GoLiveRecordResponse.builder()
                .id(r.getId())
                .tenantId(r.getTenantId())
                .tenantName(tenantName)
                .environment(r.getEnvironment())
                .buildVersion(r.getBuildVersion())
                .status(r.getStatus())
                .steps(r.getSteps())
                .performedBy(r.getPerformedBy())
                .performedByName(r.getPerformedByName())
                .startedAt(r.getStartedAt())
                .completedAt(r.getCompletedAt())
                .approvedBy(r.getApprovedBy())
                .approvedByName(r.getApprovedByName())
                .approvedAt(r.getApprovedAt())
                .approvalNote(r.getApprovalNote())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
