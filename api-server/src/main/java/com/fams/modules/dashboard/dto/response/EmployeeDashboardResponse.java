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

    @Schema(description = "Actionable items — unread notifications and items needing the employee's own explanation")
    private AlertsSummary alerts;

    @Data
    @Builder
    @Schema(description = "Actionable items summary for the employee's home screen")
    public static class AlertsSummary {

        @Schema(description = "Unread notification count (see GET /notifications for the full list)")
        private long unreadNotifications;

        @Schema(description = "Combined count of pending_review check-ins and unresolved violations "
                + "belonging to this employee that still need an explanation submitted — same set "
                + "as GET /me/exceptions, provided here as a lightweight badge count so the home "
                + "screen doesn't need to fetch and count the full list")
        private long pendingExplanations;
    }

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

        @Schema(description = "Logical closure reason when no checkout evidence exists")
        private String sessionCloseReason;

        @Schema(description = "Immutable deadline after which checkout is no longer accepted")
        private OffsetDateTime sessionExpiresAt;

        @Schema(description = "Total work minutes for this session (null if session still open)")
        private Integer workMinutes;

        @Schema(description = "True only while the session has no checkout/closure and its deadline is still in the future")
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
