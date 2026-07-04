package com.fams.modules.dashboard.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Employee dashboard — today's shifts, check-in status and monthly attendance summary")
public class EmployeeDashboardResponse {

    @Schema(description = "Active assignments and their shifts for today (empty if not assigned to any site today)")
    private List<TodayShiftInfo> todayShifts;

    @Schema(description = "Most recent check-in record for today (null if employee has not checked in today)")
    private TodayCheckinStatus checkin;

    @Schema(description = "Aggregated attendance stats for the current calendar month")
    private MonthlyAttendanceSummary monthlyAttendance;

    @Data
    @Builder
    @Schema(description = "Details of a single active assignment and its associated shift for today")
    public static class TodayShiftInfo {

        @Schema(description = "Assignment UUID")
        private UUID assignmentId;

        @Schema(description = "Site UUID")
        private UUID siteId;

        @Schema(description = "Site name")
        private String siteName;

        @Schema(description = "Employee role at this site", allowableValues = {"worker", "supervisor"})
        private String role;

        @Schema(description = "Shift details — null when the assignment has no shift attached")
        private ShiftDetail shift;
    }

    @Data
    @Builder
    @Schema(description = "Shift timing details")
    public static class ShiftDetail {

        @Schema(description = "Shift UUID")
        private UUID shiftId;

        @Schema(description = "Shift name", example = "Morning Shift")
        private String name;

        @Schema(description = "Shift start time", example = "08:00:00")
        private LocalTime startTime;

        @Schema(description = "Shift end time", example = "17:00:00")
        private LocalTime endTime;
    }

    @Data
    @Builder
    @Schema(description = "Today's check-in status for the employee")
    public static class TodayCheckinStatus {

        @Schema(description = "Check-in record UUID")
        private UUID checkinId;

        @Schema(description = "Site UUID where the employee checked in")
        private UUID siteId;

        @Schema(description = "Check-in record status", allowableValues = {"valid", "pending_review", "rejected"})
        private String status;

        @Schema(description = "When the employee checked in")
        private OffsetDateTime checkInAt;

        @Schema(description = "When the employee checked out (null if session still open)")
        private OffsetDateTime checkOutAt;

        @Schema(description = "Total work minutes for this session (null if session still open)")
        private Integer workMinutes;

        @Schema(description = "True while the check-in session is still open (no checkout)")
        private boolean open;
    }

    @Data
    @Builder
    @Schema(description = "Aggregated attendance statistics for the current calendar month")
    public static class MonthlyAttendanceSummary {

        @Schema(description = "Month in YYYY-MM format", example = "2026-07")
        private String month;

        @Schema(description = "Number of days the employee was present this month")
        private int presentDays;

        @Schema(description = "Number of days the employee arrived late")
        private int lateDays;

        @Schema(description = "Number of days the employee left early")
        private int earlyLeaveDays;

        @Schema(description = "Number of past days with an unclosed check-in session")
        private int missingCheckoutDays;

        @Schema(description = "Total overtime minutes accumulated this month")
        private int totalOtMinutes;

        @Schema(description = "Total work minutes accumulated this month")
        private int totalWorkMinutes;
    }
}
