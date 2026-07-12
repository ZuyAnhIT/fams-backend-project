package com.fams.modules.violation.controller;

import com.fams.modules.violation.dto.request.ConfirmViolationRequest;
import com.fams.modules.violation.dto.request.DismissViolationRequest;
import com.fams.modules.violation.dto.request.UpdateAttendanceImpactRequest;
import com.fams.modules.violation.dto.response.AttendanceImpactResponse;
import com.fams.modules.violation.dto.response.ViolationActionResponse;
import com.fams.modules.violation.dto.response.ViolationDetailResponse;
import com.fams.modules.violation.dto.response.ViolationListResponse;
import com.fams.modules.violation.service.ViolationService;
import com.fams.shared.dto.ExplanationResponse;
import com.fams.shared.dto.SubmitExplanationRequest;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@Slf4j
@Tag(name = "Violations", description = "Violation records and employee explanation")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/violations")
public class ViolationController {

    private final ViolationService violationService;

    public ViolationController(ViolationService violationService) {
        this.violationService = violationService;
    }

    @Operation(
        summary = "HR lists violations with search, filter, sort and pagination",
        description = "Returns a paginated list of violation records for the tenant. " +
                      "Supports filtering by employeeId, siteId, violationType, resolved status, and date range. " +
                      "Sortable by checkDate, createdAt, violationType, employeeId, or siteId. " +
                      "Requires the violations:list permission (granted to tenant_admin and hr_manager). " +
                      "Platform admins bypass permission checks."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Paginated list of violations"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "No valid JWT provided"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Caller lacks violations:list permission")
    })
    @PreAuthorize("hasAuthority('violations:list')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ViolationListResponse>>> listViolations(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Filter by employee UUID") @RequestParam(required = false) UUID employeeId,
            @Parameter(description = "Filter by site UUID") @RequestParam(required = false) UUID siteId,
            @Parameter(description = "Filter by violation type (no_response | location_fail | face_fail | liveness_fail)")
                @RequestParam(required = false) String violationType,
            @Parameter(description = "Filter by resolved status (true | false)")
                @RequestParam(required = false) Boolean resolved,
            @Parameter(description = "Filter: check date from (inclusive, ISO date e.g. 2026-07-01)")
                @RequestParam(required = false) LocalDate from,
            @Parameter(description = "Filter: check date to (inclusive, ISO date e.g. 2026-07-31)")
                @RequestParam(required = false) LocalDate to,
            @Parameter(description = "Sort field (default: checkDate)")
                @RequestParam(defaultValue = "checkDate") String sortBy,
            @Parameter(description = "Sort direction: asc | desc (default: desc)")
                @RequestParam(defaultValue = "desc") String sortDir,
            @Parameter(description = "Zero-based page index (default 0)")
                @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (default 20, max 100)")
                @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        size = Math.min(size, 100);
        log.info("HR violation list tenantId={} employeeId={} siteId={} violationType={} resolved={} page={} size={}",
                tenantId, employeeId, siteId, violationType, resolved, page, size);
        PageResponse<ViolationListResponse> result = violationService.listViolations(
                tenantId, employeeId, siteId, violationType, resolved, from, to,
                sortBy, sortDir, page, size,
                userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "HR views violation detail",
        description = "Returns full details of a single violation including the linked scheduled check summary " +
                      "and the employee's check response evidence (GPS coordinates, face image URL, liveness score). " +
                      "Requires the violations:read permission (granted to tenant_admin and hr_manager). " +
                      "Returns 404 if the violation does not exist in this tenant."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Violation detail returned",
            content = @Content(schema = @Schema(implementation = ViolationDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "No valid JWT provided"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Caller lacks violations:read permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Violation not found in this tenant")
    })
    @PreAuthorize("hasAuthority('violations:read')")
    @GetMapping("/{violationId}")
    public ResponseEntity<ApiResponse<ViolationDetailResponse>> getViolationDetail(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Violation UUID") @PathVariable UUID violationId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("HR violation detail tenantId={} violationId={}", tenantId, violationId);
        ViolationDetailResponse response = violationService.getViolationDetail(
                tenantId, violationId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "HR confirms a violation as valid",
        description = "Marks a violation as confirmed — HR has reviewed the evidence and determined the violation " +
                      "is genuine. An optional note can be attached. " +
                      "Returns 409 if the violation has already been resolved. " +
                      "Requires the violations:update permission (tenant_admin and hr_manager)."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Violation confirmed successfully",
            content = @Content(schema = @Schema(implementation = ViolationActionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "No valid JWT provided"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Caller lacks violations:update permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Violation not found in this tenant"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "Violation has already been resolved")
    })
    @PreAuthorize("hasAuthority('violations:update')")
    @PostMapping("/{violationId}/confirm")
    public ResponseEntity<ApiResponse<ViolationActionResponse>> confirmViolation(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Violation UUID") @PathVariable UUID violationId,
            @RequestBody(required = false) ConfirmViolationRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("HR confirm violation tenantId={} violationId={} userId={}",
                tenantId, violationId, userDetails.getUserId());
        ViolationActionResponse response = violationService.confirmViolation(
                tenantId, violationId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "HR dismisses a violation",
        description = "Marks a violation as dismissed — HR has reviewed the evidence and determined the violation " +
                      "should not affect the employee's attendance record. A mandatory reason must be provided. " +
                      "Returns 400 if reason is blank. Returns 409 if the violation has already been resolved. " +
                      "Requires the violations:update permission (tenant_admin and hr_manager)."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Violation dismissed successfully",
            content = @Content(schema = @Schema(implementation = ViolationActionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error — reason is required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "No valid JWT provided"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Caller lacks violations:update permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Violation not found in this tenant"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "Violation has already been resolved")
    })
    @PreAuthorize("hasAuthority('violations:update')")
    @PostMapping("/{violationId}/dismiss")
    public ResponseEntity<ApiResponse<ViolationActionResponse>> dismissViolation(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Violation UUID") @PathVariable UUID violationId,
            @Valid @RequestBody DismissViolationRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("HR dismiss violation tenantId={} violationId={} userId={}",
                tenantId, violationId, userDetails.getUserId());
        ViolationActionResponse response = violationService.dismissViolation(
                tenantId, violationId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "HR updates attendance impact flag for a violation",
        description = "Sets or clears the affectsAttendance flag on a violation. " +
                      "When true, the violation is taken into account when calculating the employee's " +
                      "attendance record. Can be updated at any time (before or after resolution). " +
                      "Requires the violations:update permission (tenant_admin and hr_manager)."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Attendance impact updated",
            content = @Content(schema = @Schema(implementation = AttendanceImpactResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error — affectsAttendance is required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "No valid JWT provided"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Caller lacks violations:update permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Violation not found in this tenant")
    })
    @PreAuthorize("hasAuthority('violations:update')")
    @PatchMapping("/{violationId}/attendance-impact")
    public ResponseEntity<ApiResponse<AttendanceImpactResponse>> updateAttendanceImpact(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Violation UUID") @PathVariable UUID violationId,
            @Valid @RequestBody UpdateAttendanceImpactRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("HR update attendance impact tenantId={} violationId={} affectsAttendance={} userId={}",
                tenantId, violationId, request.getAffectsAttendance(), userDetails.getUserId());
        AttendanceImpactResponse response = violationService.updateAttendanceImpact(
                tenantId, violationId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Employee submits explanation for a violation",
        description = "Allows the authenticated employee to attach a written explanation and optional photo URL " +
                      "to a violation record that belongs to them. " +
                      "Used to provide context to HR before the violation is resolved — for example, " +
                      "explaining why a random check was missed or why GPS was outside the geofence. " +
                      "Returns 403 if the violation belongs to a different employee. " +
                      "Returns 404 if the violation does not exist in this tenant."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Explanation recorded successfully",
            content = @Content(schema = @Schema(implementation = ExplanationResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error — note is required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Violation belongs to a different employee"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Violation not found or employee profile not found")
    })
    @PostMapping("/{violationId}/explain")
    public ResponseEntity<ApiResponse<ExplanationResponse>> explainViolation(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Violation UUID") @PathVariable UUID violationId,
            @Valid @RequestBody SubmitExplanationRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Employee explain violation tenantId={} violationId={} userId={}",
                tenantId, violationId, userDetails.getUserId());
        ExplanationResponse response = violationService.explainViolation(
                tenantId, violationId, request, userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
