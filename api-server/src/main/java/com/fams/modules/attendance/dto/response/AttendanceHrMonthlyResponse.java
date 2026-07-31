package com.fams.modules.attendance.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@Schema(description = "Monthly attendance aggregate for one employee at one site — HR/Admin view")
public class AttendanceHrMonthlyResponse {

    @Schema(description = "Tenant ID")
    private UUID tenantId;

    @Schema(description = "Employee ID")
    private UUID employeeId;

    @Schema(description = "Employee full name (firstName + lastName)", example = "Nguyen Van A")
    private String employeeName;

    @Schema(description = "Site ID")
    private UUID siteId;

    @Schema(description = "Site name", example = "Headquarters")
    private String siteName;

    @Schema(description = "Year", example = "2026")
    private int year;

    @Schema(description = "Month (1–12)", example = "6")
    private int month;

    @Schema(description = "Days the employee has at least one check-in record", example = "22")
    private int presentDays;

    @Schema(description = "Total work minutes across all sessions for the month", example = "10560")
    private int totalWorkMinutes;

    @Schema(description = "Number of days the employee arrived late", example = "2")
    private int lateDays;

    @Schema(description = "Cumulative late minutes across all late days", example = "35")
    private int totalLateMinutes;

    @Schema(description = "Number of days the employee left early", example = "1")
    private int earlyLeaveDays;

    @Schema(description = "Cumulative early-leave minutes this month", example = "20")
    private int totalEarlyLeaveMinutes;

    @Schema(description = "Total overtime minutes for the month", example = "90")
    private int totalOtMinutes;

    @Schema(description = "Days flagged as missing checkout (past day with open session)", example = "0")
    private int missingCheckoutDays;

    @Schema(description = "Days with at least one unconfirmed (pending_review) session this month — exclude or "
            + "double-check these before finalizing payroll export, totals above may be understated for them",
            example = "0")
    private int daysWithPendingReview;

    @Schema(description = "Days with at least one HR-rejected session this month (excluded from all totals above)",
            example = "0")
    private int daysWithRejectedSession;
}
