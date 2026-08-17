package com.fams.modules.assignment.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Employee assignment to a site")
public class AssignmentResponse {

    @Schema(description = "Assignment UUID")
    private UUID id;

    @Schema(description = "Tenant UUID")
    private UUID tenantId;

    @Schema(description = "Site UUID")
    private UUID siteId;

    @Schema(description = "Employee UUID")
    private UUID employeeId;

    @Schema(description = "Shift template UUID, or null if not linked to a specific shift")
    private UUID shiftId;

    @Schema(description = "Brief site details — only populated on cross-site listings (e.g. GET .../assignments/me) " +
            "where the site isn't already implied by the request path; null on the site-scoped list endpoint",
            nullable = true)
    private SiteSummary siteSummary;

    @Schema(description = "Brief employee details, avoids a follow-up lookup when rendering a list of assignments")
    private EmployeeSummary employeeSummary;

    @Schema(description = "Brief shift details (name/hours), null if not linked to a shift")
    private ShiftSummary shiftSummary;

    @Schema(description = "Assignment start date", example = "2026-07-01")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @Schema(description = "Assignment end date (inclusive), or null for open-ended", example = "2026-12-31")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    @Schema(description = "Weekdays this assignment is active on, or null if active every day",
            example = "[\"MONDAY\",\"WEDNESDAY\",\"FRIDAY\"]")
    private Set<DayOfWeek> daysOfWeek;

    @Schema(description = "Employee's role at the site: worker or supervisor", example = "worker")
    private String role;

    @Schema(description = "Status: active or cancelled", example = "active")
    private String status;

    @Schema(description = "UUID of the user who cancelled this assignment, null if never cancelled")
    private UUID cancelledBy;

    @Schema(description = "Cancellation timestamp, null if never cancelled")
    private OffsetDateTime cancelledAt;

    @Schema(description = "Optional notes")
    private String notes;

    @Schema(description = "UUID of the user who created this assignment")
    private UUID createdBy;

    @Schema(description = "Creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp")
    private OffsetDateTime updatedAt;

    @Data
    @Builder
    @Schema(description = "Brief site details embedded in the assignment response")
    public static class SiteSummary {
        @Schema(description = "Site UUID")
        private UUID id;

        @Schema(description = "Site name", example = "Công trình Quận 1")
        private String name;
    }

    @Data
    @Builder
    @Schema(description = "Brief employee details embedded in the assignment response")
    public static class EmployeeSummary {
        @Schema(description = "Employee UUID")
        private UUID id;

        @Schema(description = "Internal employee code", example = "EMP-001")
        private String employeeCode;

        @Schema(description = "Full name", example = "John Doe")
        private String fullName;

        @Schema(description = "Employment status", example = "active",
                allowableValues = {"active", "inactive", "terminated"})
        private String status;
    }

    @Data
    @Builder
    @Schema(description = "Brief shift details embedded in the assignment response")
    public static class ShiftSummary {
        @Schema(description = "Shift UUID")
        private UUID id;

        @Schema(description = "Shift name", example = "Morning Shift")
        private String name;

        @Schema(description = "Shift start time (HH:mm)", example = "08:00")
        @JsonFormat(pattern = "HH:mm")
        private java.time.LocalTime startTime;

        @Schema(description = "Shift end time (HH:mm)", example = "17:00")
        @JsonFormat(pattern = "HH:mm")
        private java.time.LocalTime endTime;

        @Schema(description = "Shift status: active or inactive", example = "active")
        private String status;
    }
}
