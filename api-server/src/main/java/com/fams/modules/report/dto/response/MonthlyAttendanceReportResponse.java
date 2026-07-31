package com.fams.modules.report.dto.response;

import com.fams.modules.attendance.dto.response.AttendanceHrMonthlyResponse;
import com.fams.shared.pagination.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@Schema(description = "Monthly attendance report — tenant-wide aggregate stats plus paginated per-employee records")
public class MonthlyAttendanceReportResponse {

    @Schema(description = "Report year", example = "2026")
    private int year;

    @Schema(description = "Report month (1–12)", example = "7")
    private int month;

    @Schema(description = "Site filter applied to this report (null = all sites in the tenant)")
    private UUID siteId;

    @Schema(description = "Number of distinct employees with at least one attendance record in the month", example = "45")
    private int totalEmployees;

    @Schema(description = "Sum of presentDays across all employees for the month", example = "990")
    private int totalPresentDays;

    @Schema(description = "Sum of totalWorkMinutes across all employees for the month", example = "475200")
    private int totalWorkMinutes;

    @Schema(description = "Sum of lateDays across all employees for the month", example = "18")
    private int totalLateDays;

    @Schema(description = "Sum of totalLateMinutes across all employees for the month", example = "540")
    private int totalLateMinutes;

    @Schema(description = "Sum of earlyLeaveDays across all employees for the month", example = "5")
    private int totalEarlyLeaveDays;

    @Schema(description = "Sum of totalEarlyLeaveMinutes across all employees for the month", example = "120")
    private int totalEarlyLeaveMinutes;

    @Schema(description = "Sum of missingCheckoutDays across all employees for the month", example = "2")
    private int totalMissingCheckoutDays;

    @Schema(description = "Sum of totalOtMinutes across all employees for the month", example = "1800")
    private int totalOtMinutes;

    @Schema(description = "Number of employee+site rows with at least one unconfirmed (pending_review) session "
            + "this month — non-zero means the report is NOT payroll-final; resolve these before exporting.",
            example = "0")
    private int totalRowsWithPendingReview;

    @Schema(description = "Number of employee+site rows with at least one HR-rejected session this month",
            example = "0")
    private int totalRowsWithRejectedSession;

    @Schema(description = "Paginated per-employee monthly attendance aggregates")
    private PageResponse<AttendanceHrMonthlyResponse> records;
}
