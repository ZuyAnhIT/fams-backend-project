package com.fams.modules.subscription.service;

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

    public PlanLimitEnforcementService(TenantSubscriptionRepository subscriptionRepository,
                                       PlanLimitsRepository planLimitsRepository,
                                       EmployeeRepository employeeRepository,
                                       SiteRepository siteRepository,
                                       ScheduledCheckRepository scheduledCheckRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.planLimitsRepository = planLimitsRepository;
        this.employeeRepository = employeeRepository;
        this.siteRepository = siteRepository;
        this.scheduledCheckRepository = scheduledCheckRepository;
    }

    /** Throws PlanLimitExceededException if the tenant has reached their max employee count. */
    @Transactional(readOnly = true)
    public void assertEmployeeLimit(UUID tenantId) {
        PlanLimits limits = resolveLimits(tenantId).orElse(null);
        if (limits == null || limits.getMaxEmployees() == null) return;

        long current = employeeRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
        if (current >= limits.getMaxEmployees()) {
            throw new PlanLimitExceededException(
                    "Employee limit reached: plan allows " + limits.getMaxEmployees()
                    + ", currently at " + current);
        }
    }

    /** Throws PlanLimitExceededException if the tenant has reached their max site count. */
    @Transactional(readOnly = true)
    public void assertSiteLimit(UUID tenantId) {
        PlanLimits limits = resolveLimits(tenantId).orElse(null);
        if (limits == null || limits.getMaxSites() == null) return;

        long current = siteRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
        if (current >= limits.getMaxSites()) {
            throw new PlanLimitExceededException(
                    "Site limit reached: plan allows " + limits.getMaxSites()
                    + ", currently at " + current);
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

    /** Throws PlanLimitExceededException if the tenant has no remaining random check quota this month. */
    @Transactional(readOnly = true)
    public void assertRandomCheckLimit(UUID tenantId) {
        if (getRemainingRandomChecks(tenantId) <= 0) {
            PlanLimits limits = resolveLimits(tenantId).orElse(null);
            int cap = limits != null && limits.getMaxRandomChecksPerMonth() != null
                    ? limits.getMaxRandomChecksPerMonth() : 0;
            throw new PlanLimitExceededException(
                    "Random check monthly limit reached: plan allows " + cap + " per month");
        }
    }

    private Optional<PlanLimits> resolveLimits(UUID tenantId) {
        return subscriptionRepository.findByTenantId(tenantId)
                .map(TenantSubscription::getPlanId)
                .flatMap(planLimitsRepository::findByPlanId);
    }
}
