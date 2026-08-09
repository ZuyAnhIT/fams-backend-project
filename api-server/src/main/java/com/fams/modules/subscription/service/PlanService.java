package com.fams.modules.subscription.service;

import com.fams.modules.subscription.dto.request.CreatePlanRequest;
import com.fams.modules.subscription.dto.request.UpdatePlanRequest;
import com.fams.modules.subscription.dto.response.PlanResponse;
import com.fams.modules.subscription.entity.Plan;
import com.fams.modules.subscription.entity.TenantSubscription;
import com.fams.modules.subscription.entity.TenantSubscription.SubscriptionStatus;
import com.fams.modules.subscription.repository.PlanRepository;
import com.fams.modules.subscription.repository.TenantSubscriptionRepository;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.PlanDeactivationBlockedException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class PlanService {

    private static final Set<String> SORTABLE_FIELDS = Set.of("sortOrder", "name", "displayName", "priceMonthly", "priceYearly", "createdAt", "updatedAt");
    private static final List<SubscriptionStatus> MIGRATABLE_STATUSES = List.of(SubscriptionStatus.TRIAL, SubscriptionStatus.ACTIVE);

    private final PlanRepository planRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final TenantRepository tenantRepository;
    private final PlanLimitEnforcementService planLimitEnforcementService;
    private final AuditLogService auditLogService;

    public PlanService(PlanRepository planRepository,
                        TenantSubscriptionRepository tenantSubscriptionRepository,
                        TenantRepository tenantRepository,
                        PlanLimitEnforcementService planLimitEnforcementService,
                        AuditLogService auditLogService) {
        this.planRepository = planRepository;
        this.tenantSubscriptionRepository = tenantSubscriptionRepository;
        this.tenantRepository = tenantRepository;
        this.planLimitEnforcementService = planLimitEnforcementService;
        this.auditLogService = auditLogService;
    }

    /** #31 (docs/api/backend-feature-audit-2026-08-07.md): plans are platform-wide (no single
     *  tenant "owns" one), so tenantId is null here — same convention as TotpService's
     *  account-level events. A plan's price/limits change affects every tenant subscribed to
     *  it, which is exactly the kind of blast-radius action that belongs in the audit trail. */
    private void recordPlanAudit(UUID actorId, String action, UUID planId,
                                  Map<String, Object> oldValue, Map<String, Object> newValue) {
        try {
            auditLogService.record(
                    null, actorId, null,
                    "Plan", planId.toString(), action,
                    oldValue, newValue,
                    HttpRequestUtils.currentRequestId(),
                    HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception ex) {
            log.warn("Failed to record audit log for {} planId={}: {}", action, planId, ex.getMessage());
        }
    }

    private Map<String, Object> planAuditSnapshot(Plan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", plan.getName());
        m.put("displayName", plan.getDisplayName());
        m.put("priceMonthly", plan.getPriceMonthly());
        m.put("priceYearly", plan.getPriceYearly());
        m.put("sortOrder", plan.getSortOrder());
        m.put("isActive", plan.isActive());
        return m;
    }

    @Transactional(readOnly = true)
    public PageResponse<PlanResponse> listPlans(boolean activeOnly, String sortBy, String sortDir, int page, int size) {
        String resolvedSortBy = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "sortOrder";
        Sort sort = "desc".equalsIgnoreCase(sortDir)
                ? Sort.by(resolvedSortBy).descending()
                : Sort.by(resolvedSortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Plan> plans = activeOnly
                ? planRepository.findByIsActiveTrueAndDeletedAtIsNull(pageable)
                : planRepository.findByDeletedAtIsNull(pageable);
        return PageResponse.from(plans.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public PlanResponse getPlan(UUID id) {
        Plan plan = planRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + id));
        return toResponse(plan);
    }

    @Transactional
    public PlanResponse createPlan(CreatePlanRequest request, UUID callerUserId) {
        if (planRepository.findByNameAndDeletedAtIsNull(request.getName()).isPresent()) {
            throw new DuplicateResourceException("Plan name '" + request.getName() + "' already exists");
        }

        Plan plan = Plan.builder()
                .name(request.getName())
                .displayName(request.getDisplayName())
                .description(request.getDescription())
                .priceMonthly(request.getPriceMonthly())
                .priceYearly(request.getPriceYearly())
                .sortOrder(request.getSortOrder())
                .build();

        planRepository.save(plan);
        log.info("Plan created: id={} name={}", plan.getId(), plan.getName());
        recordPlanAudit(callerUserId, "plan_created", plan.getId(), null, planAuditSnapshot(plan));
        return toResponse(plan);
    }

    @Transactional
    public PlanResponse updatePlan(UUID id, UpdatePlanRequest request, UUID callerUserId) {
        Plan plan = planRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + id));

        Map<String, Object> before = planAuditSnapshot(plan);

        if (StringUtils.hasText(request.getDisplayName())) plan.setDisplayName(request.getDisplayName());
        if (request.getDescription() != null)              plan.setDescription(request.getDescription().isBlank() ? null : request.getDescription());
        if (request.getPriceMonthly() != null)             plan.setPriceMonthly(request.getPriceMonthly());
        if (request.getPriceYearly() != null)              plan.setPriceYearly(request.getPriceYearly());
        if (request.getSortOrder() != null)                plan.setSortOrder(request.getSortOrder());

        boolean isDeactivating = Boolean.FALSE.equals(request.getIsActive()) && plan.isActive();
        if (isDeactivating) {
            migrateTenantsOffPlan(plan, request.getMigrateToPlanId());
        }
        if (request.getIsActive() != null) plan.setActive(request.getIsActive());

        planRepository.save(plan);
        log.info("Plan updated: id={} name={}", id, plan.getName());
        recordPlanAudit(callerUserId, "plan_updated", plan.getId(), before, planAuditSnapshot(plan));
        return toResponse(plan);
    }

    /**
     * Issue #8 (docs/issues/ISSUES.md): a plan being deactivated must not silently strand its
     * current tenants — migrate them to another plan first, refusing the whole operation if any
     * tenant's current usage wouldn't fit the target plan's limits (rather than partially
     * migrating some tenants and leaving others behind on a dead plan).
     */
    private void migrateTenantsOffPlan(Plan plan, UUID migrateToPlanId) {
        List<TenantSubscription> affected = tenantSubscriptionRepository
                .findAllByPlanIdAndStatusIn(plan.getId(), MIGRATABLE_STATUSES);
        if (affected.isEmpty()) return;

        if (migrateToPlanId == null) {
            throw new PlanDeactivationBlockedException(
                    "Còn " + affected.size() + " công ty đang dùng gói \"" + plan.getDisplayName()
                    + "\". Cần chỉ định migrateToPlanId để tự động chuyển các công ty này sang gói khác trước khi tắt.");
        }
        if (migrateToPlanId.equals(plan.getId())) {
            throw new PlanDeactivationBlockedException("Gói chuyển đến phải khác gói đang tắt.");
        }
        Plan targetPlan = planRepository.findByIdAndDeletedAtIsNull(migrateToPlanId)
                .orElseThrow(() -> new ResourceNotFoundException("Target plan not found: " + migrateToPlanId));
        if (!targetPlan.isActive()) {
            throw new PlanDeactivationBlockedException(
                    "Gói chuyển đến (\"" + targetPlan.getDisplayName() + "\") hiện cũng đang bị tắt, không thể chuyển tới.");
        }

        Map<UUID, String> tenantLabelsById = new LinkedHashMap<>();
        for (TenantSubscription sub : affected) {
            Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(sub.getTenantId()).orElse(null);
            tenantLabelsById.put(sub.getTenantId(), tenant != null ? tenant.getName() : sub.getTenantId().toString());
        }

        List<String> violations = planLimitEnforcementService.findLimitViolationsForPlan(tenantLabelsById, migrateToPlanId);
        if (!violations.isEmpty()) {
            throw new PlanDeactivationBlockedException(
                    "Không thể chuyển " + violations.size() + " công ty sang gói \"" + targetPlan.getDisplayName()
                    + "\" do vượt hạn mức: " + String.join("; ", violations)
                    + ". Vui lòng chọn gói khác hoặc xử lý giảm số lượng trước.");
        }

        for (TenantSubscription sub : affected) {
            sub.setPlanId(migrateToPlanId);
            tenantSubscriptionRepository.save(sub);
        }
        log.info("Migrated {} tenants from plan {} to plan {} as part of deactivation",
                affected.size(), plan.getId(), migrateToPlanId);
    }

    private PlanResponse toResponse(Plan plan) {
        return PlanResponse.builder()
                .id(plan.getId())
                .name(plan.getName())
                .displayName(plan.getDisplayName())
                .description(plan.getDescription())
                .priceMonthly(plan.getPriceMonthly())
                .priceYearly(plan.getPriceYearly())
                .sortOrder(plan.getSortOrder())
                .isActive(plan.isActive())
                .createdAt(plan.getCreatedAt())
                .updatedAt(plan.getUpdatedAt())
                .build();
    }
}
