package com.fams.modules.subscription.service;

import com.fams.modules.audit.repository.AuditLogRepository;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.subscription.entity.PlanLimits;
import com.fams.modules.subscription.entity.TenantSubscription;
import com.fams.modules.subscription.repository.PlanLimitsRepository;
import com.fams.modules.subscription.repository.TenantSubscriptionRepository;
import com.fams.shared.exception.PlanLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class PlanLimitEnforcementService {

    private final TenantSubscriptionRepository subscriptionRepository;
    private final PlanLimitsRepository planLimitsRepository;
    private final EmployeeRepository employeeRepository;
    private final SiteRepository siteRepository;
    private final ScheduledCheckRepository scheduledCheckRepository;
    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;

    public PlanLimitEnforcementService(TenantSubscriptionRepository subscriptionRepository,
                                       PlanLimitsRepository planLimitsRepository,
                                       EmployeeRepository employeeRepository,
                                       SiteRepository siteRepository,
                                       ScheduledCheckRepository scheduledCheckRepository,
                                       AuditLogService auditLogService,
                                       AuditLogRepository auditLogRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.planLimitsRepository = planLimitsRepository;
        this.employeeRepository = employeeRepository;
        this.siteRepository = siteRepository;
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.auditLogService = auditLogService;
        this.auditLogRepository = auditLogRepository;
    }

    /** #135 (2026-08-19): AC calls for auditing denied attempts — entirely absent before.
     *  Best-effort: a logging failure must never block the caller from seeing the real
     *  PlanLimitExceededException. Centralized here (not at each call site) so every assert*
     *  method logs the same way regardless of caller. */
    private void recordDenied(UUID tenantId, UUID actorUserId, String limitType, String message) {
        try {
            auditLogService.record(
                    tenantId, actorUserId, null,
                    "PlanLimit", limitType, "PLAN_LIMIT_DENIED",
                    null, java.util.Map.of("limitType", limitType, "reason", message),
                    com.fams.shared.security.HttpRequestUtils.currentRequestId(),
                    com.fams.shared.security.HttpRequestUtils.currentIpAddress(),
                    com.fams.shared.security.HttpRequestUtils.currentUserAgent());
        } catch (Exception e) {
            log.warn("Failed to record audit log for PLAN_LIMIT_DENIED tenantId={} limitType={}: {}",
                    tenantId, limitType, e.getMessage());
        }
    }

    /** Throws PlanLimitExceededException if the tenant has reached their max employee count.
     *  @param actorUserId who attempted the action, for the denial audit log — may be null. */
    @Transactional(readOnly = true)
    public void assertEmployeeLimit(UUID tenantId, UUID actorUserId) {
        PlanLimits limits = resolveLimits(tenantId).orElse(null);
        if (limits == null || limits.getMaxEmployees() == null) return;

        long current = employeeRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
        if (current >= limits.getMaxEmployees()) {
            String message = "Employee limit reached: plan allows " + limits.getMaxEmployees()
                    + ", currently at " + current;
            recordDenied(tenantId, actorUserId, "employee", message);
            throw new PlanLimitExceededException(message);
        }
    }

    /** Throws PlanLimitExceededException if the tenant has reached their max site count.
     *  @param actorUserId who attempted the action, for the denial audit log — may be null. */
    @Transactional(readOnly = true)
    public void assertSiteLimit(UUID tenantId, UUID actorUserId) {
        PlanLimits limits = resolveLimits(tenantId).orElse(null);
        if (limits == null || limits.getMaxSites() == null) return;

        long current = siteRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
        if (current >= limits.getMaxSites()) {
            String message = "Site limit reached: plan allows " + limits.getMaxSites()
                    + ", currently at " + current;
            recordDenied(tenantId, actorUserId, "site", message);
            throw new PlanLimitExceededException(message);
        }
    }

    /**
     * Returns the number of random checks the tenant can still issue this calendar month.
     * Returns Integer.MAX_VALUE when there is no limit.
     */
    @Transactional(readOnly = true)
    public int getRemainingRandomChecks(UUID tenantId) {
        PlanLimits limits = resolveLimits(tenantId).orElse(null);
        if (limits == null || limits.getMaxRandomChecksPerMonth() == null) return Integer.MAX_VALUE;

        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        long used = scheduledCheckRepository.countByTenantAndMonth(tenantId, monthStart, monthEnd);

        int remaining = (int) Math.max(0, limits.getMaxRandomChecksPerMonth() - used);
        log.debug("Random check quota for tenant {}: limit={} used={} remaining={}",
                tenantId, limits.getMaxRandomChecksPerMonth(), used, remaining);
        return remaining;
    }

    /** Throws PlanLimitExceededException if the tenant has no remaining random check quota this month.
     *  @param actorUserId who attempted the action, for the denial audit log — may be null. */
    @Transactional(readOnly = true)
    public void assertRandomCheckLimit(UUID tenantId, UUID actorUserId) {
        if (getRemainingRandomChecks(tenantId) <= 0) {
            PlanLimits limits = resolveLimits(tenantId).orElse(null);
            int cap = limits != null && limits.getMaxRandomChecksPerMonth() != null
                    ? limits.getMaxRandomChecksPerMonth() : 0;
            String message = "Random check monthly limit reached: plan allows " + cap + " per month";
            recordDenied(tenantId, actorUserId, "random_check", message);
            throw new PlanLimitExceededException(message);
        }
    }

    /** Throws PlanLimitExceededException if the tenant has reached their max export count this month.
     *  Reuses the existing EXPORT_* audit log entries as the usage counter (#135, 2026-08-19) —
     *  every export call site already writes one, so no separate counter table is needed.
     *  @param actorUserId who attempted the action, for the denial audit log — may be null. */
    @Transactional(readOnly = true)
    public void assertExportLimit(UUID tenantId, UUID actorUserId) {
        PlanLimits limits = resolveLimits(tenantId).orElse(null);
        if (limits == null || limits.getMaxExportsPerMonth() == null) return;

        LocalDate today = LocalDate.now();
        OffsetDateTime monthStart = today.withDayOfMonth(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime monthEnd = monthStart.plusMonths(1);
        long used = auditLogRepository.countExportsByTenantInRange(tenantId, monthStart, monthEnd);

        if (used >= limits.getMaxExportsPerMonth()) {
            String message = "Export monthly limit reached: plan allows " + limits.getMaxExportsPerMonth()
                    + " per month, currently at " + used;
            recordDenied(tenantId, actorUserId, "export", message);
            throw new PlanLimitExceededException(message);
        }
    }

    /**
     * Issue #8 (docs/issues/ISSUES.md): checks whether tenants' CURRENT usage (not "would one
     * more exceed" like the assert* methods above) already exceeds a candidate target plan's
     * limits — used before migrating tenants off a plan being deactivated. Returns one
     * human-readable reason per violation found; empty list means every tenant fits.
     */
    @Transactional(readOnly = true)
    public List<String> findLimitViolationsForPlan(Map<UUID, String> tenantLabelsById, UUID targetPlanId) {
        PlanLimits limits = planLimitsRepository.findByPlanId(targetPlanId).orElse(null);
        if (limits == null) return List.of();

        List<String> violations = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : tenantLabelsById.entrySet()) {
            UUID tenantId = entry.getKey();
            String label = entry.getValue();
            if (limits.getMaxEmployees() != null) {
                long employees = employeeRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
                if (employees > limits.getMaxEmployees()) {
                    violations.add(label + ": " + employees + " nhân viên (vượt giới hạn " + limits.getMaxEmployees() + " của gói mới)");
                }
            }
            if (limits.getMaxSites() != null) {
                long sites = siteRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
                if (sites > limits.getMaxSites()) {
                    violations.add(label + ": " + sites + " công trình (vượt giới hạn " + limits.getMaxSites() + " của gói mới)");
                }
            }
        }
        return violations;
    }

    private Optional<PlanLimits> resolveLimits(UUID tenantId) {
        return subscriptionRepository.findByTenantId(tenantId)
                .map(TenantSubscription::getPlanId)
                .flatMap(planLimitsRepository::findByPlanId);
    }
}
