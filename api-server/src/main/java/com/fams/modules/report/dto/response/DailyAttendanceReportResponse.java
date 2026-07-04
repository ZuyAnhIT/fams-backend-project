package com.fams.modules.report.dto.response;

import com.fams.modules.attendance.dto.response.AttendanceSummaryResponse;
import com.fams.shared.pagination.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Daily attendance report — aggregate stats plus paginated individual records for one date")
public class DailyAttendanceReportResponse {

    @Schema(description = "Report date", example = "2026-07-04")
    private LocalDate date;

    @Schema(description = "Site filter applied to this report (null = all sites in the tenant)")
    private UUID siteId;

    @Schema(description = "Number of employees with at least one attendance record on this date", example = "42")
    private int totalPresent;

    @Schema(description = "Number of active-assigned employees with no attendance record on this date", example = "3")
    private int totalAbsent;

    @Schema(description = "Number of employees whose first check-in was after their shift start time", example = "7")
    private int totalLate;

    @Schema(description = "Number of employees who checked out before their shift end time", example = "2")
    private int totalEarlyLeave;

    @Schema(description = "Number of employees with an open check-in session on a past date", example = "1")
    private int totalMissingCheckout;

    @Schema(description = "Sum of totalWorkMinutes across all present employees for this date", example = "20160")
    private int totalWorkMinutes;

    @Schema(description = "Sum of otMinutes across all present employees for this date", example = "240")
    private int totalOtMinutes;

    @Schema(description = "Employee IDs that have active assignments but no attendance record on this date")
    private List<UUID> absentEmployeeIds;

    @Schema(description = "Paginated individual attendance records for this date")
    private PageResponse<AttendanceSummaryResponse> records;
}
