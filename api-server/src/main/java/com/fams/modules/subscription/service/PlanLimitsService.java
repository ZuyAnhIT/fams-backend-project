package com.fams.modules.subscription.service;

import com.fams.modules.subscription.dto.request.UpdatePlanLimitsRequest;
import com.fams.modules.subscription.dto.response.PlanLimitsResponse;
import com.fams.modules.subscription.entity.PlanLimits;
import com.fams.modules.subscription.repository.PlanLimitsRepository;
import com.fams.modules.subscription.repository.PlanRepository;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class PlanLimitsService {

    private final PlanRepository planRepository;
    private final PlanLimitsRepository planLimitsRepository;
    private final AuditLogService auditLogService;

    public PlanLimitsService(PlanRepository planRepository, PlanLimitsRepository planLimitsRepository,
                              AuditLogService auditLogService) {
        this.planRepository = planRepository;
        this.planLimitsRepository = planLimitsRepository;
        this.auditLogService = auditLogService;
    }

    /** #31 (docs/api/backend-feature-audit-2026-08-07.md): plan limits directly gate
     *  PlanLimitEnforcementService (see docs/api/backend-feature-audit-2026-08-01.md #21) —
     *  raising/lowering a limit changes what every tenant on that plan can do platform-wide. */
    private void recordLimitsAudit(UUID actorId, UUID planId, Map<String, Object> oldValue, Map<String, Object> newValue) {
        try {
            auditLogService.record(
                    null, actorId, null,
                    "PlanLimits", planId.toString(), "plan_limits_updated",
                    oldValue, newValue,
                    HttpRequestUtils.currentRequestId(),
                    HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception ex) {
            log.warn("Failed to record audit log for plan_limits_updated planId={}: {}", planId, ex.getMessage());
        }
    }

    private Map<String, Object> limitsAuditSnapshot(PlanLimits l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxEmployees", l.getMaxEmployees());
        m.put("maxSites", l.getMaxSites());
        m.put("maxStorageGb", l.getMaxStorageGb());
        m.put("maxRandomChecksPerMonth", l.getMaxRandomChecksPerMonth());
        return m;
    }

    @Transactional
    public PlanLimitsResponse getLimits(UUID planId) {
        assertPlanExists(planId);
        PlanLimits limits = planLimitsRepository.findByPlanId(planId)
                .orElseGet(() -> createUnlimited(planId));
        return toResponse(limits);
    }

    @Transactional
    public PlanLimitsResponse updateLimits(UUID planId, UpdatePlanLimitsRequest request, UUID callerUserId) {
        assertPlanExists(planId);
        PlanLimits limits = planLimitsRepository.findByPlanId(planId)
                .orElseGet(() -> createUnlimited(planId));

        Map<String, Object> before = limitsAuditSnapshot(limits);

        if (request.isClearMaxEmployees()) {
            limits.setMaxEmployees(null);
        } else if (request.getMaxEmployees() != null) {
            limits.setMaxEmployees(request.getMaxEmployees());
        }

        if (request.isClearMaxSites()) {
            limits.setMaxSites(null);
        } else if (request.getMaxSites() != null) {
            limits.setMaxSites(request.getMaxSites());
        }

        if (request.isClearMaxStorageGb()) {
            limits.setMaxStorageGb(null);
        } else if (request.getMaxStorageGb() != null) {
            limits.setMaxStorageGb(request.getMaxStorageGb());
        }

        if (request.isClearMaxRandomChecksPerMonth()) {
            limits.setMaxRandomChecksPerMonth(null);
        } else if (request.getMaxRandomChecksPerMonth() != null) {
            limits.setMaxRandomChecksPerMonth(request.getMaxRandomChecksPerMonth());
        }

        planLimitsRepository.save(limits);
        log.info("Plan limits updated: planId={}", planId);
        recordLimitsAudit(callerUserId, planId, before, limitsAuditSnapshot(limits));
        return toResponse(limits);
    }

    private void assertPlanExists(UUID planId) {
        planRepository.findByIdAndDeletedAtIsNull(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + planId));
    }

    private PlanLimits createUnlimited(UUID planId) {
        PlanLimits limits = PlanLimits.builder().planId(planId).build();
        return planLimitsRepository.save(limits);
    }

    private PlanLimitsResponse toResponse(PlanLimits l) {
        return PlanLimitsResponse.builder()
                .id(l.getId())
                .planId(l.getPlanId())
                .maxEmployees(l.getMaxEmployees())
                .maxSites(l.getMaxSites())
                .maxStorageGb(l.getMaxStorageGb())
                .maxRandomChecksPerMonth(l.getMaxRandomChecksPerMonth())
                .updatedAt(l.getUpdatedAt())
                .build();
    }
}
