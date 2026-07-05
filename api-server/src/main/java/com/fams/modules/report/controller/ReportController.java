package com.fams.modules.report.controller;

import com.fams.modules.report.dto.response.DailyAttendanceReportResponse;
import com.fams.modules.report.dto.response.FaceIdReportResponse;
import com.fams.modules.report.dto.response.MonthlyAttendanceReportResponse;
import com.fams.modules.report.dto.response.SitePresenceReportResponse;
import com.fams.modules.report.dto.response.ViolationReportResponse;
import com.fams.modules.report.service.ReportService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "Reports", description = "Attendance and workforce reporting endpoints for HR/Admin")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @Operation(
        summary = "Daily attendance report (HR/Admin)",
        description = "Returns aggregate statistics for a specific date plus a paginated list of individual " +
                      "employee attendance records. Absent employees — those with active assignments on the " +
                      "date but no attendance record — are identified and returned as a list of IDs. " +
                      "Requires reports:list permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Daily attendance report returned successfully",
            content = @Content(schema = @Schema(implementation = DailyAttendanceReportResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Missing or invalid date parameter"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Forbidden — reports:list permission required")
    })
    @GetMapping("/attendance/daily")
    public ResponseEntity<ApiResponse<DailyAttendanceReportResponse>> getDailyAttendanceReport(
            @PathVariable UUID tenantId,
            @Parameter(description = "Report date (yyyy-MM-dd)", required = true)
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "Restrict report to one site (optional — omit for all sites in the tenant)")
                @RequestParam(required = false) UUID siteId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal FamsUserDetails caller) {

        DailyAttendanceReportResponse result = reportService.getDailyAttendanceReport(
                tenantId, date, siteId, page, size,
                caller.getUserId(), caller.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Monthly attendance report (HR/Admin)",
        description = "Returns tenant-wide aggregate statistics for a given year/month plus a paginated list of " +
                      "per-employee monthly attendance records. Useful for payroll preparation. " +
                      "Optionally filter by siteId. Requires reports:list permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Monthly attendance report returned successfully",
            content = @Content(schema = @Schema(implementation = MonthlyAttendanceReportResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid year or month parameter"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Forbidden — reports:list permission required")
    })
    @GetMapping("/attendance/monthly")
    public ResponseEntity<ApiResponse<MonthlyAttendanceReportResponse>> getMonthlyAttendanceReport(
            @PathVariable UUID tenantId,
            @Parameter(description = "Year (e.g. 2026)", required = true) @RequestParam int year,
            @Parameter(description = "Month (1–12)", required = true) @RequestParam int month,
            @Parameter(description = "Restrict report to one site (optional — omit for all sites)")
                @RequestParam(required = false) UUID siteId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal FamsUserDetails caller) {

        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month must be between 1 and 12");
        }

        MonthlyAttendanceReportResponse result = reportService.getMonthlyAttendanceReport(
                tenantId, year, month, siteId, page, size,
                caller.getUserId(), caller.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Export monthly attendance to Excel (HR/Admin)",
        description = "Generates and downloads an Excel (.xlsx) file containing the monthly attendance " +
                      "timesheet: one row per employee+site combination with present days, work minutes, " +
                      "late, early-leave, OT, and missing-checkout totals. " +
                      "Optionally filter by siteId. Requires reports:export permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Excel file downloaded successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid year or month parameter"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Forbidden — reports:export permission required")
    })
    @GetMapping("/attendance/export")
    public ResponseEntity<byte[]> exportMonthlyAttendance(
            @PathVariable UUID tenantId,
            @Parameter(description = "Year (e.g. 2026)", required = true) @RequestParam int year,
            @Parameter(description = "Month (1–12)", required = true) @RequestParam int month,
            @Parameter(description = "Restrict export to one site (optional)")
                @RequestParam(required = false) UUID siteId,
            @AuthenticationPrincipal FamsUserDetails caller) {

        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month must be between 1 and 12");
        }

        byte[] content = reportService.exportMonthlyAttendance(
                tenantId, year, month, siteId,
                caller.getUserId(), caller.isPlatformAdmin());

        String filename = String.format("attendance-%d-%02d.xlsx", year, month);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(content.length);

        return ResponseEntity.ok().headers(headers).body(content);
    }

    @Operation(
        summary = "Violation report by period (HR/Admin)",
        description = "Returns aggregate violation statistics for a date range — total count, resolved vs " +
                      "unresolved, attendance impact count, and breakdowns by violation type, site, and " +
                      "employee — plus a paginated list of individual violation records. " +
                      "All filters (from, to, siteId, employeeId, violationType) are optional. " +
                      "Requires reports:list permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Violation report returned successfully",
            content = @Content(schema = @Schema(implementation = ViolationReportResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Forbidden — reports:list permission required")
    })
    @GetMapping("/violations")
    public ResponseEntity<ApiResponse<ViolationReportResponse>> getViolationReport(
            @PathVariable UUID tenantId,
            @Parameter(description = "Start date inclusive (yyyy-MM-dd, optional)")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date inclusive (yyyy-MM-dd, optional)")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Filter by site ID (optional)")
                @RequestParam(required = false) UUID siteId,
            @Parameter(description = "Filter by employee ID (optional)")
                @RequestParam(required = false) UUID employeeId,
            @Parameter(description = "Filter by violation type: no_response | location_fail | face_fail | liveness_fail (optional)")
                @RequestParam(required = false) String violationType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal FamsUserDetails caller) {

        ViolationReportResponse result = reportService.getViolationReport(
                tenantId, from, to, siteId, employeeId, violationType, page, size,
                caller.getUserId(), caller.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Export violation list to Excel (HR/Admin)",
        description = "Generates and downloads an Excel (.xlsx) file containing all violations matching the " +
                      "supplied filters — one row per violation with type, date, resolved status, attendance " +
                      "impact, employee note, and timestamps. All filters are optional. " +
                      "Requires reports:export permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Excel file downloaded successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Forbidden — reports:export permission required")
    })
    @GetMapping("/violations/export")
    public ResponseEntity<byte[]> exportViolations(
            @PathVariable UUID tenantId,
            @Parameter(description = "Start date inclusive (yyyy-MM-dd, optional)")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date inclusive (yyyy-MM-dd, optional)")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Filter by site ID (optional)")
                @RequestParam(required = false) UUID siteId,
            @Parameter(description = "Filter by employee ID (optional)")
                @RequestParam(required = false) UUID employeeId,
            @Parameter(description = "Filter by violation type (optional)")
                @RequestParam(required = false) String violationType,
            @AuthenticationPrincipal FamsUserDetails caller) {

        byte[] content = reportService.exportViolations(
                tenantId, from, to, siteId, employeeId, violationType,
                caller.getUserId(), caller.isPlatformAdmin());

        String filename = "violations-export.xlsx";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(content.length);

        return ResponseEntity.ok().headers(headers).body(content);
    }

    @Operation(
        summary = "Face ID enrollment status report (HR/Admin)",
        description = "Returns aggregate Face ID enrollment stats (enrolled, pending, not_enrolled, revoked) " +
                      "for all active employees, plus a paginated list of per-employee rows. " +
                      "Optionally filter by Face ID status. Requires reports:list permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Face ID enrollment report returned successfully",
            content = @Content(schema = @Schema(implementation = FaceIdReportResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Forbidden — reports:list permission required")
    })
    @GetMapping("/face-id/enrollment")
    public ResponseEntity<ApiResponse<FaceIdReportResponse>> getFaceIdEnrollmentReport(
            @PathVariable UUID tenantId,
            @Parameter(description = "Filter by Face ID status: enrolled | pending | not_enrolled | revoked (optional)")
                @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal FamsUserDetails caller) {

        FaceIdReportResponse result = reportService.getFaceIdEnrollmentReport(
                tenantId, status, page, size,
                caller.getUserId(), caller.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Site presence report — real-time (HR/Admin/Supervisor)",
        description = "Returns a real-time snapshot of employee presence at each site: how many are currently " +
                      "checked in (open session) versus the number with active assignments today. " +
                      "Optionally filter to a single site. Requires reports:list permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Site presence report returned successfully",
            content = @Content(schema = @Schema(implementation = SitePresenceReportResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Forbidden — reports:list permission required")
    })
    @GetMapping("/sites/presence")
    public ResponseEntity<ApiResponse<SitePresenceReportResponse>> getSitePresenceReport(
            @PathVariable UUID tenantId,
            @Parameter(description = "Restrict to a single site (optional — omit for all sites)")
                @RequestParam(required = false) UUID siteId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal FamsUserDetails caller) {

        SitePresenceReportResponse result = reportService.getSitePresenceReport(
                tenantId, siteId, page, size,
                caller.getUserId(), caller.isPlatformAdmin());

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
