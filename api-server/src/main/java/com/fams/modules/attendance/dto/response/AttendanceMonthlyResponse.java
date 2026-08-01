package com.fams.modules.attendance.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Monthly attendance aggregate for one employee")
public class AttendanceMonthlyResponse {

    @Schema(description = "Tenant ID")
    private UUID tenantId;

    @Schema(description = "Employee ID")
    private UUID employeeId;

    @Schema(description = "Year", example = "2026")
    private int year;

    @Schema(description = "Month (1–12)", example = "6")
    private int month;

    @Schema(description = "Number of days the employee has at least one check-in", example = "22")
    private int presentDays;

    @Schema(description = "Total work minutes across all sessions this month", example = "10560")
    private int totalWorkMinutes;

    @Schema(description = "Number of days the employee arrived late", example = "2")
    private int lateDays;

    @Schema(description = "Cumulative late minutes across all late days", example = "35")
    private int totalLateMinutes;

    @Schema(description = "Number of days the employee left early", example = "1")
    private int earlyLeaveDays;

    @Schema(description = "Cumulative early-leave minutes across all early-leave days", example = "20")
    private int totalEarlyLeaveMinutes;

    @Schema(description = "Total overtime minutes worked this month", example = "90")
    private int totalOtMinutes;

    @Schema(description = "Number of days flagged as missing checkout (past day, open session)", example = "0")
    private int missingCheckoutDays;

    @Schema(description = "Days with at least one unconfirmed (pending_review) session — those days' totals "
            + "above may be understated until HR resolves them. Not payroll-final.", example = "0")
    private int daysWithPendingReview;

    @Schema(description = "Days with at least one HR-rejected session (excluded from all totals above)", example = "0")
    private int daysWithRejectedSession;

    @Schema(description = "Days with at least one failed/no_response random (spot) check this month — "
            + "informational only, does not affect totals above.", example = "0")
    private int daysWithRandomCheckFailure;

    @Schema(description = "Daily attendance summaries for the month, sorted by date ascending")
    private List<AttendanceSummaryResponse> dailySummaries;
}
