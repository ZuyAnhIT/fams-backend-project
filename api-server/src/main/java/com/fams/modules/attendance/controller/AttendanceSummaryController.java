package com.fams.modules.attendance.controller;

import com.fams.modules.attendance.dto.request.AdjustAttendanceSummaryRequest;
import com.fams.modules.attendance.dto.request.UnlockAttendanceSummaryRequest;
import com.fams.modules.attendance.dto.response.AttendanceHrMonthlyResponse;
import com.fams.modules.attendance.dto.response.AttendanceMonthlyResponse;
import com.fams.modules.attendance.dto.response.AttendanceSummaryResponse;
import com.fams.modules.attendance.service.AttendanceSummaryService;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@Slf4j
@Tag(name = "Attendance", description = "Attendance summary endpoints — daily aggregation per employee/site")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/attendance")
public class AttendanceSummaryController {

    private final AttendanceSummaryService attendanceSummaryService;

    public AttendanceSummaryController(AttendanceSummaryService attendanceSummaryService) {
        this.attendanceSummaryService = attendanceSummaryService;
    }

    @Operation(
        summary = "List attendance summaries (HR/Admin)",
        description = "Returns a paginated list of daily attendance summaries for the tenant. " +
                      "Supports filtering by employee, site, status, and date range. " +
                      "Requires attendance:list permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Paginated attendance summaries",
            content = @Content(schema = @Schema(implementation = AttendanceSummaryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403", description = "Forbidden — attendance:list permission required")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AttendanceSummaryResponse>>> listSummaries(
            @PathVariable UUID tenantId,
            @Parameter(description = "Filter by employee ID") @RequestParam(required = false) UUID employeeId,
            @Parameter(description = "Filter by site ID") @RequestParam(required = false) UUID siteId,
            @Parameter(description = "Filter by status: present | incomplete") @RequestParam(required = false) String status,
            @Parameter(description = "Start date (inclusive, yyyy-MM-dd)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date (inclusive, yyyy-MM-dd)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal FamsUserDetails caller) {

        PageResponse<AttendanceSummaryResponse> result = attendanceSummaryService.listSummaries(
                tenantId, employeeId, siteId, status, from, to, page, size,
                caller.getUserId(), caller.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Get attendance summary detail (HR/Admin)",
        description = "Returns the full daily attendance summary record by ID. " +
                      "Requires attendance:read permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Attendance summary found",
            content = @Content(schema = @Schema(implementation = AttendanceSummaryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403", description = "Forbidden — attendance:read permission required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Attendance summary not found")
    })
    @GetMapping("/{summaryId}")
    public ResponseEntity<ApiResponse<AttendanceSummaryResponse>> getSummary(
            @PathVariable UUID tenantId,
            @PathVariable UUID summaryId,
            @AuthenticationPrincipal FamsUserDetails caller) {

        AttendanceSummaryResponse result = attendanceSummaryService.getSummary(
                tenantId, summaryId, caller.getUserId(), caller.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Get my attendance summaries (Employee)",
        description = "Returns a paginated list of the authenticated employee's own daily attendance summaries. " +
                      "Requires attendance:read permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Paginated attendance summaries for the calling employee",
            content = @Content(schema = @Schema(implementation = AttendanceSummaryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "No employee profile found for this user")
    })
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<PageResponse<AttendanceSummaryResponse>>> getMyAttendance(
            @PathVariable UUID tenantId,
            @Parameter(description = "Start date (inclusive, yyyy-MM-dd)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date (inclusive, yyyy-MM-dd)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal FamsUserDetails caller) {

        PageResponse<AttendanceSummaryResponse> result = attendanceSummaryService.getMyAttendance(
                tenantId, caller.getUserId(), from, to, page, size);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "List monthly attendance aggregates by employee/site (HR/Admin)",
        description = "Returns a paginated list of monthly attendance aggregates, one record per " +
                      "employee+site combination. Supports filtering by employeeId and/or siteId. " +
                      "Requires attendance:list permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Paginated monthly attendance aggregates",
            content = @Content(schema = @Schema(implementation = AttendanceHrMonthlyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "Invalid year or month"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403", description = "Forbidden — attendance:list permission required")
    })
    @GetMapping("/monthly")
    public ResponseEntity<ApiResponse<PageResponse<AttendanceHrMonthlyResponse>>> listMonthlyAttendance(
            @PathVariable UUID tenantId,
            @Parameter(description = "Year (e.g. 2026)", required = true) @RequestParam int year,
            @Parameter(description = "Month (1–12)", required = true) @RequestParam int month,
            @Parameter(description = "Filter by employee ID") @RequestParam(required = false) UUID employeeId,
            @Parameter(description = "Filter by site ID") @RequestParam(required = false) UUID siteId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal FamsUserDetails caller) {

        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month must be between 1 and 12");
        }

        PageResponse<AttendanceHrMonthlyResponse> result = attendanceSummaryService.listMonthlyAttendance(
                tenantId, employeeId, siteId, year, month, page, size,
                caller.getUserId(), caller.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Get my monthly attendance aggregate (Employee)",
        description = "Returns total work days, work minutes, late days, early-leave days, OT minutes, " +
                      "and missing-checkout count for the authenticated employee in the requested month. " +
                      "Also includes the list of daily summaries for that month."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Monthly attendance aggregate",
            content = @Content(schema = @Schema(implementation = AttendanceMonthlyResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "Invalid year or month"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "No employee profile found for this user")
    })
    @GetMapping("/me/monthly")
    public ResponseEntity<ApiResponse<AttendanceMonthlyResponse>> getMyMonthlyAttendance(
            @PathVariable UUID tenantId,
            @Parameter(description = "Year (e.g. 2026)", required = true) @RequestParam int year,
            @Parameter(description = "Month (1–12)", required = true) @RequestParam int month,
            @AuthenticationPrincipal FamsUserDetails caller) {

        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month must be between 1 and 12");
        }

        AttendanceMonthlyResponse result = attendanceSummaryService
                .getMyMonthlyAttendance(tenantId, caller.getUserId(), year, month);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "HR adjust attendance summary",
        description = "Allows HR/Admin to manually override fields on a daily attendance summary record. " +
                      "All fields are optional — only supplied fields are updated. " +
                      "A reason is required and stored on the record for auditing. " +
                      "Note: the nightly recompute job will overwrite adjustments for the current day; " +
                      "adjustments to past dates are safe. Requires attendance:list permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Attendance summary updated",
            content = @Content(schema = @Schema(implementation = AttendanceSummaryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error — invalid field values or missing reason"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing attendance:list permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Attendance summary not found")
    })
    @PatchMapping("/{summaryId}/adjust")
    public ResponseEntity<ApiResponse<AttendanceSummaryResponse>> adjustSummary(
            @PathVariable UUID tenantId,
            @PathVariable UUID summaryId,
            @Valid @RequestBody AdjustAttendanceSummaryRequest request,
            @AuthenticationPrincipal FamsUserDetails caller) {

        log.info("HR adjust attendance summary tenantId={} summaryId={} by={}",
                tenantId, summaryId, caller.getUserId());
        AttendanceSummaryResponse result = attendanceSummaryService.adjustSummary(
                tenantId, summaryId, request, caller.getUserId(), caller.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Unlock and recompute an HR-adjusted attendance summary (HR/Admin)",
        description = "An HR-adjusted summary (see .../adjust) is permanently protected from automatic " +
                      "recompute so its manual override is never silently overwritten. Use this endpoint when " +
                      "new source data has legitimately arrived (e.g. a late-approved check-in) and the summary " +
                      "should be released back to normal automatic aggregation. Clears the adjustment, re-runs " +
                      "the standard recompute for that employee/site/date, and records an audit log entry " +
                      "(before/after totalWorkMinutes, previous adjustment reason, unlock reason). " +
                      "A reason is required. Requires attendance:list permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Attendance summary unlocked and recomputed",
            content = @Content(schema = @Schema(implementation = AttendanceSummaryResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error — missing reason"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing attendance:list permission or site not allowed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Attendance summary not found")
    })
    @PostMapping("/{summaryId}/unlock-and-recompute")
    public ResponseEntity<ApiResponse<AttendanceSummaryResponse>> unlockAndRecompute(
            @PathVariable UUID tenantId,
            @PathVariable UUID summaryId,
            @Valid @RequestBody UnlockAttendanceSummaryRequest request,
            @AuthenticationPrincipal FamsUserDetails caller) {

        log.info("HR unlock-and-recompute attendance summary tenantId={} summaryId={} by={}",
                tenantId, summaryId, caller.getUserId());
        AttendanceSummaryResponse result = attendanceSummaryService.unlockAndRecompute(
                tenantId, summaryId, request.getReason(), caller.getUserId(), caller.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Trigger attendance summary recompute for a date (HR/Admin)",
        description = "Recomputes attendance summaries for all sessions whose attendance date equals the given date. " +
                      "Idempotent — safe to call multiple times. Use this to backfill missing_checkout flags " +
                      "or re-run calculations after manual data corrections. " +
                      "Requires attendance:list permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Recompute triggered successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403", description = "Forbidden — attendance:list permission required")
    })
    @PostMapping("/recompute")
    public ResponseEntity<ApiResponse<String>> recomputeForDate(
            @PathVariable UUID tenantId,
            @Parameter(description = "Date to recompute (yyyy-MM-dd)", required = true)
                @RequestParam @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "Restrict recompute to one site — required if the caller is "
                    + "scoped to more than one site") @RequestParam(required = false) UUID siteId,
            @AuthenticationPrincipal FamsUserDetails caller) {

        // Permission + site-scope resolution now live in the service (triggerRecompute) —
        // consistent with every other endpoint here, and critically, properly scoped to
        // tenantId so this can never touch another tenant's data (see service Javadoc).
        attendanceSummaryService.triggerRecompute(
                tenantId, siteId, date, caller.getUserId(), caller.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success("Recompute triggered for date: " + date));
    }
}
