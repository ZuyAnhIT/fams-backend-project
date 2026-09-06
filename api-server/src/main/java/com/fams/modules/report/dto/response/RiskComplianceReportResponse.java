package com.fams.modules.report.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RiskComplianceReportResponse(
        LocalDate from,
        LocalDate to,
        RiskKpis kpis,
        Map<String, Long> byType,
        Map<String, Long> aging,
        Funnel funnel,
        List<RiskTrendPoint> trend,
        List<SiteRisk> siteRisk,
        List<RepeatOffender> repeatOffenders) {

    public record RiskKpis(long violations, long checkins, double violationsPer100Checkins,
                           long unresolved, long overdue, double averageResolutionHours,
                           double medianResolutionHours, double acceptedExplanationRate,
                           double randomCheckPassRate, double faceEnrollmentRate,
                           long attendanceImpactViolations) {}

    public record Funnel(long detected, long explained, long reviewed,
                         long confirmed, long dismissed) {}

    public record RiskTrendPoint(LocalDate date, String violationType, long count) {}

    public record SiteRisk(UUID siteId, String siteName, String violationType, long count,
                           double per100Checkins) {}

    public record RepeatOffender(UUID employeeId, String employeeName, String employeeCode,
                                 long violations, long unresolved) {}
}
