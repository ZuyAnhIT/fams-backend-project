package com.fams.modules.attendance.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Daily attendance summary for one employee at one site")
public class AttendanceSummaryResponse {

    @Schema(description = "Summary record ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "Tenant ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID tenantId;

    @Schema(description = "Employee ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID employeeId;

    @Schema(description = "Employee full name (firstName + lastName)", example = "Nguyen Van A")
    private String employeeName;

    @Schema(description = "Site ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID siteId;

    @Schema(description = "Site name", example = "Headquarters")
    private String siteName;

    @Schema(description = "Shift ID (nullable)", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID shiftId;

    @Schema(description = "Assignment ID (nullable)", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID assignmentId;

    @Schema(description = "Attendance date in site local timezone", example = "2026-06-27")
    private LocalDate attendanceDate;

    @Schema(description = "Timestamp of the first check-in session of the day")
    private OffsetDateTime firstCheckinAt;

    @Schema(description = "Timestamp of the last check-out session of the day (null if any session is still open)")
    private OffsetDateTime lastCheckoutAt;

    @Schema(description = "Total paid work minutes summed across all sessions", example = "480")
    private int totalWorkMinutes;

    @Schema(description = "Number of check-in sessions for this day", example = "1")
    private int sessionCount;

    @Schema(description = "present = all sessions have checkout; incomplete = at least one open session",
            example = "present", allowableValues = {"present", "incomplete"})
    private String status;

    @Schema(description = "True if the first check-in was after the shift start time", example = "false")
    private boolean late;

    @Schema(description = "Minutes between shift start and first check-in (0 if not late or no shift)", example = "0")
    private int lateMinutes;

    @Schema(description = "True if the last check-out was before the shift end time", example = "false")
    private boolean earlyLeave;

    @Schema(description = "Minutes between last check-out and shift end (0 if not early leave, no shift, or no checkout)", example = "0")
    private int earlyLeaveMinutes;

    @Schema(description = "Total overtime minutes worked beyond shift end, capped per session by lateCheckoutMinutes. 0 when shift.allowOvertime=false or no overtime worked.", example = "0")
    private int otMinutes;

    @Schema(description = "True when the attendance date is in the past and at least one session has no check-out. Set by the nightly catch-up job.", example = "false")
    private boolean missingCheckout;

    @Schema(description = "Record creation timestamp")
    private OffsetDateTime createdAt;

    @Schema(description = "Record last-updated timestamp")
    private OffsetDateTime updatedAt;

    @Schema(description = "Reason provided by HR when manually adjusting this summary (null if never adjusted)")
    private String adjustmentReason;
}
