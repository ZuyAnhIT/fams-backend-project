package com.fams.modules.report.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PlatformRevenueReportResponse(
        LocalDate from,
        LocalDate to,
        RevenueKpis kpis,
        Map<String, Long> subscriptionStatus,
        Map<String, Long> paymentStatus,
        List<RevenueTrendPoint> trend,
        List<PlanRevenue> byPlan,
        Funnel funnel,
        List<ExpiringSubscription> expiringSubscriptions) {

    public record RevenueKpis(long collectedRevenue, long currentMrr,
                              double trialConversionRate, double renewalRate,
                              double churnRate, long averageRevenuePerCompany,
                              double paymentSuccessRate) {}

    public record RevenueTrendPoint(String period, long collectedRevenue, long mrr) {}

    public record PlanRevenue(UUID planId, String planName, long collectedRevenue,
                              long paidOrders, long activeSubscriptions, double revenueShare) {}

    public record Funnel(long trial, long paid, long activated, long renewed) {}

    public record ExpiringSubscription(UUID tenantId, String tenantName, String planName,
                                       String billingCycle, OffsetDateTime expiresAt,
                                       long daysRemaining) {}
}
