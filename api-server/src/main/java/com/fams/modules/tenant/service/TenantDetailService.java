package com.fams.modules.tenant.service;

import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.subscription.entity.Plan;
import com.fams.modules.subscription.entity.PlanLimits;
import com.fams.modules.subscription.entity.TenantSubscription;
import com.fams.modules.subscription.repository.PlanLimitsRepository;
import com.fams.modules.subscription.repository.PlanRepository;
import com.fams.modules.subscription.repository.TenantSubscriptionRepository;
import com.fams.modules.tenant.dto.response.TenantDetailResponse;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Slf4j
@Service
public class TenantDetailService {

    private final TenantRepository tenantRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final PlanLimitsRepository planLimitsRepository;
    private final EmployeeRepository employeeRepository;
    private final SiteRepository siteRepository;
    private final ScheduledCheckRepository scheduledCheckRepository;
    private final TenantStorageUsageService tenantStorageUsageService;

    public TenantDetailService(TenantRepository tenantRepository,
                               TenantSubscriptionRepository subscriptionRepository,
                               PlanRepository planRepository,
                               PlanLimitsRepository planLimitsRepository,
                               EmployeeRepository employeeRepository,
                               SiteRepository siteRepository,
                               ScheduledCheckRepository scheduledCheckRepository,
                               TenantStorageUsageService tenantStorageUsageService) {
        this.tenantRepository = tenantRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.planLimitsRepository = planLimitsRepository;
        this.employeeRepository = employeeRepository;
        this.siteRepository = siteRepository;
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.tenantStorageUsageService = tenantStorageUsageService;
    }

    /**
     * @param isPrivilegedCaller true for a Platform Admin or a caller holding the
     *                           {@code tenants:read} permission (e.g. Platform Staff) — this
     *                           flag is resolved by the controller since it needs the caller's
     *                           granted authorities, not just the platform-admin bit. A tenant's
     *                           own owner is always allowed regardless of this flag: they need
     *                           their own plan/limits/usage to build a "Billing & Usage" screen.
     */
    @Transactional(readOnly = true)
    public TenantDetailResponse getDetail(UUID tenantId, UUID userId, boolean isPrivilegedCaller) {
        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!isPrivilegedCaller && !userId.equals(tenant.getOwnerId())) {
            throw new AccessDeniedException("You do not have permission to view this tenant's detail");
        }

        // Subscription (optional — tenant may not have one yet)
        TenantSubscription sub = subscriptionRepository.findByTenantId(tenantId).orElse(null);
        Plan plan = (sub != null)
                ? planRepository.findByIdAndDeletedAtIsNull(sub.getPlanId()).orElse(null)
                : null;
        PlanLimits limits = (plan != null)
                ? planLimitsRepository.findByPlanId(plan.getId()).orElse(null)
                : null;

        // Usage stats
        long employeeCount = employeeRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
        long siteCount = siteRepository.countByTenantIdAndDeletedAtIsNull(tenantId);

        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        long monthlyChecks = scheduledCheckRepository.countByTenantAndMonth(tenantId, monthStart, monthEnd);
        double storageGb = tenantStorageUsageService.computeUsedBytes(tenantId) / 1_073_741_824.0;

        log.info("Tenant detail fetched: id={}", tenantId);

        return TenantDetailResponse.builder()
                .id(tenant.getId())
                .name(tenant.getName())
                .slug(tenant.getSlug())
                .domain(tenant.getDomain())
                .logoUrl(tenant.getLogoUrl())
                .industry(tenant.getIndustry())
                .countryCode(tenant.getCountryCode())
                .timezone(tenant.getTimezone())
                .locale(tenant.getLocale())
                .status(tenant.getStatus())
                .ownerId(tenant.getOwnerId())
                .createdAt(tenant.getCreatedAt())
                // Subscription
                .planName(plan != null ? plan.getName() : null)
                .planDisplayName(plan != null ? plan.getDisplayName() : null)
                .subscriptionStatus(sub != null ? sub.getStatus() : null)
                .billingCycle(sub != null ? sub.getBillingCycle() : null)
                .subscriptionStartedAt(sub != null ? sub.getStartedAt() : null)
                .subscriptionExpiresAt(sub != null ? sub.getExpiresAt() : null)
                // Limits
                .maxEmployees(limits != null ? limits.getMaxEmployees() : null)
                .maxSites(limits != null ? limits.getMaxSites() : null)
                .maxStorageGb(limits != null ? limits.getMaxStorageGb() : null)
                .maxRandomChecksPerMonth(limits != null ? limits.getMaxRandomChecksPerMonth() : null)
                // Usage
                .currentEmployeeCount(employeeCount)
                .currentSiteCount(siteCount)
                .currentMonthRandomChecks(monthlyChecks)
                .currentStorageGb(storageGb)
                .build();
    }
}
