package com.fams.modules.report.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PlatformCustomerHealthReportResponse(
        LocalDate from,
        LocalDate to,
        HealthKpis kpis,
        List<GrowthPoint> growth,
        Map<String, Long> moduleUsage,
        Map<String, Long> inactivityBuckets,
        List<TenantHealth> tenantsAtRisk,
        List<TenantHealth> tenantsNearPlanLimit) {

    public record HealthKpis(long totalTenants, long newTenants, long activeTenants,
                             long suspendedTenants, long totalUsers, long activeUsers7d,
                             long activeUsers30d, long employees, long sites, long checkins) {}

    public record GrowthPoint(String period, long newTenants, long newUsers) {}

    public record TenantHealth(UUID tenantId, String tenantName, String planName,
                               String subscriptionStatus, int healthScore, String riskLevel,
                               double maxPlanUsagePercent, long employees, long sites,
                               long checkins30d, long randomChecks30d,
                               OffsetDateTime lastActivityAt, long inactiveDays,
                               String recommendedAction) {}
}
