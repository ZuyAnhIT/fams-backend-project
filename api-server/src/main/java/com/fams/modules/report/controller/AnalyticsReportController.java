package com.fams.modules.report.controller;

import com.fams.modules.report.dto.response.PlatformCustomerHealthReportResponse;
import com.fams.modules.report.dto.response.PlatformRevenueReportResponse;
import com.fams.modules.report.dto.response.RiskComplianceReportResponse;
import com.fams.modules.report.dto.response.WorkforceEffectivenessReportResponse;
import com.fams.modules.report.service.AnalyticsReportService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Tag(name = "Analytics reports", description = "Decision-support analytics for platform and tenant administrators")
@RestController
public class AnalyticsReportController {

    private final AnalyticsReportService analytics;

    public AnalyticsReportController(AnalyticsReportService analytics) {
        this.analytics = analytics;
    }

    @Operation(summary = "Platform revenue and subscription analytics")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/api/v1/platform/reports/revenue")
    public ResponseEntity<ApiResponse<PlatformRevenueReportResponse>> revenue(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) UUID planId,
            @RequestParam(required = false) String subscriptionStatus,
            @RequestParam(defaultValue = "30") int expiryDays) {
        DateRange range = range(from, to, 365 * 3);
        return ResponseEntity.ok(ApiResponse.success(
                analytics.platformRevenue(range.from(), range.to(), expiryDays,
                        tenantId, planId, subscriptionStatus)));
    }

    @Operation(summary = "Platform customer growth and health analytics")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/api/v1/platform/reports/customer-health")
    public ResponseEntity<ApiResponse<PlatformCustomerHealthReportResponse>> customerHealth(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) UUID planId,
            @RequestParam(required = false) String subscriptionStatus) {
        DateRange range = range(from, to, 365 * 3);
        return ResponseEntity.ok(ApiResponse.success(
                analytics.platformCustomerHealth(range.from(), range.to(),
                        tenantId, planId, subscriptionStatus)));
    }

    @Operation(summary = "Tenant workforce and attendance effectiveness analytics")
    @PreAuthorize("hasAuthority('reports:list')")
    @GetMapping("/api/v1/tenants/{tenantId}/reports/workforce-effectiveness")
    public ResponseEntity<ApiResponse<WorkforceEffectivenessReportResponse>> workforce(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) UUID shiftId,
            @RequestParam(required = false) UUID employeeId,
            @AuthenticationPrincipal FamsUserDetails caller) {
        DateRange range = range(from, to, 366);
        return ResponseEntity.ok(ApiResponse.success(analytics.workforce(
                tenantId, range.from(), range.to(), siteId, workspaceId, shiftId, employeeId,
                caller.getUserId(), caller.isPlatformAdmin())));
    }

    @Operation(summary = "Tenant violation risk and compliance analytics")
    @PreAuthorize("hasAuthority('reports:list')")
    @GetMapping("/api/v1/tenants/{tenantId}/reports/risk-compliance")
    public ResponseEntity<ApiResponse<RiskComplianceReportResponse>> risk(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) UUID shiftId,
            @RequestParam(required = false) UUID employeeId,
            @AuthenticationPrincipal FamsUserDetails caller) {
        DateRange range = range(from, to, 366);
        return ResponseEntity.ok(ApiResponse.success(analytics.risk(
                tenantId, range.from(), range.to(), siteId, workspaceId, shiftId, employeeId,
                caller.getUserId(), caller.isPlatformAdmin())));
    }

    private DateRange range(LocalDate from, LocalDate to, int maxDays) {
        LocalDate effectiveTo = to == null ? LocalDate.now() : to;
        LocalDate effectiveFrom = from == null ? effectiveTo.minusDays(29) : from;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) + 1 > maxDays) {
            throw new IllegalArgumentException("report period exceeds the supported range of " + maxDays + " days");
        }
        return new DateRange(effectiveFrom, effectiveTo);
    }

    private record DateRange(LocalDate from, LocalDate to) {}
}
