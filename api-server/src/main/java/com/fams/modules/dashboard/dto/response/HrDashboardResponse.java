package com.fams.modules.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
@Schema(description = "HR/Admin dashboard — personnel, attendance, violations and site overview")
public class HrDashboardResponse {

    @Schema(description = "Personnel headcount overview")
    private PersonnelOverview personnel;

    @Schema(description = "Attendance snapshot for today")
    private AttendanceOverview attendance;

    @Schema(description = "Violation status overview")
    private ViolationOverview violations;

    @Schema(description = "Site and on-site presence overview")
    private SiteOverview sites;

    @Data
    @Builder
    @Schema(description = "Employee headcount statistics for the tenant")
    public static class PersonnelOverview {

        @Schema(description = "Total active employees in the tenant")
        private long totalEmployees;

        @Schema(description = "New employees added in the current calendar month")
        private long newThisMonth;
    }

    @Data
    @Builder
    @Schema(description = "Attendance summary for today (tenant's own timezone, falls back to UTC if unset)")
    public static class AttendanceOverview {

        @Schema(description = "Number of employees with an attendance record for today")
        private long presentToday;

        @Schema(description = "Number of employees who arrived late today")
        private long lateToday;

        @Schema(description = "Number of employees currently on-site (open check-in session)")
        private long onSiteNow;

        @Schema(description = "Check-ins today still flagged pending_review, awaiting HR override (#120, 2026-08-18)")
        private long pendingReview;

        @Schema(description = "Attendance summaries today flagged missingCheckout — employee checked in but never checked out (#120, 2026-08-18)")
        private long missingCheckoutToday;
    }

    @Data
    @Builder
    @Schema(description = "Violation counts and breakdown for the tenant")
    public static class ViolationOverview {

        @Schema(description = "Total unresolved violations")
        private long unresolved;

        @Schema(description = "Violations resolved in the current calendar month")
        private long resolvedThisMonth;

        @Schema(description = "Unresolved violation counts grouped by type")
        private Map<String, Long> unresolvedByType;
    }

    @Data
    @Builder
    @Schema(description = "Site count and on-site presence for the tenant")
    public static class SiteOverview {

        @Schema(description = "Total active (non-deleted) sites in the tenant")
        private long totalSites;

        @Schema(description = "Number of employees currently on-site (open check-in session)")
        private long employeesOnSiteNow;
    }
}
