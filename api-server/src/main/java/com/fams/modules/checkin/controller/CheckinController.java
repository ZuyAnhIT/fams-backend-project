package com.fams.modules.checkin.controller;

import com.fams.modules.checkin.dto.request.OverrideCheckinRequest;
import com.fams.shared.dto.ExplanationResponse;
import com.fams.shared.dto.SubmitExplanationRequest;
import com.fams.modules.checkin.dto.request.SubmitCheckinRequest;
import com.fams.modules.checkin.dto.request.SubmitCheckoutRequest;
import com.fams.modules.checkin.dto.response.AvailableSiteResponse;
import com.fams.modules.checkin.dto.response.CheckinDetailResponse;
import com.fams.modules.checkin.dto.response.CheckinResponse;
import com.fams.modules.checkin.service.CheckinService;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "Check-in", description = "Employee check-in and check-out operations")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/checkin")
public class CheckinController {

    private final CheckinService checkinService;

    public CheckinController(CheckinService checkinService) {
        this.checkinService = checkinService;
    }

    @Operation(
        summary = "Get available sites for check-in today",
        description = "Returns the list of sites the authenticated employee is assigned to today " +
                      "(assignments where startDate <= today <= endDate and status = 'active'). " +
                      "Each entry includes site details, the assigned shift (if any), and the active geofence (if any). " +
                      "Callable by any authenticated employee with a linked employee profile. Requires checkins:read permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "List of available sites returned (may be empty if not assigned anywhere today)",
            content = @Content(schema = @Schema(implementation = AvailableSiteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized — valid JWT required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "No employee profile found for this user in the given tenant")
    })
    @GetMapping("/available-sites")
    public ResponseEntity<ApiResponse<List<AvailableSiteResponse>>> getAvailableSites(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get available check-in sites tenantId={} userId={}", tenantId, userDetails.getUserId());
        List<AvailableSiteResponse> sites = checkinService.getAvailableSites(tenantId, userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(sites));
    }

    @Operation(
        summary = "Submit GPS check-in",
        description = "Records a check-in for the authenticated employee at the specified site using GPS coordinates. " +
                      "The employee must have an active assignment at the site today. " +
                      "If the site has an active geofence the GPS point is validated via PostGIS. " +
                      "Status is 'valid' when inside the geofence (or no geofence configured), " +
                      "'pending_review' when outside. " +
                      "Returns 409 if an open check-in already exists for this assignment. " +
                      "Requires checkins:create permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "Check-in recorded successfully",
            content = @Content(schema = @Schema(implementation = CheckinResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error — missing or invalid fields"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Employee profile not found, site not found, or no active assignment today"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "Open check-in already exists for this assignment — must check out first")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<CheckinResponse>> submitCheckin(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Valid @RequestBody SubmitCheckinRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Submit check-in tenantId={} siteId={} userId={}", tenantId, request.getSiteId(), userDetails.getUserId());
        CheckinResponse response = checkinService.submitCheckin(tenantId, request, userDetails.getUserId());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(
        summary = "List check-ins (HR/Admin)",
        description = "Returns a paginated, filterable, sortable list of all check-in records in the tenant. " +
                      "Requires checkins:list permission (platform admins bypass the check). " +
                      "Filter by employeeId, siteId, status, and/or check-in date range. " +
                      "Sort by: checkInAt (default), checkOutAt, status, siteId, employeeId, createdAt."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Paginated list returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing checkins:list permission")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<CheckinResponse>>> listCheckins(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Filter by employee UUID") @RequestParam(required = false) UUID employeeId,
            @Parameter(description = "Filter by site UUID")     @RequestParam(required = false) UUID siteId,
            @Parameter(description = "Filter by status (valid | pending_review | rejected)")
                @RequestParam(required = false) String status,
            @Parameter(description = "Filter: check-in from (inclusive, ISO-8601)")
                @RequestParam(required = false) OffsetDateTime from,
            @Parameter(description = "Filter: check-in to (inclusive, ISO-8601)")
                @RequestParam(required = false) OffsetDateTime to,
            @Parameter(description = "Sort field (default: checkInAt)")
                @RequestParam(defaultValue = "checkInAt") String sortBy,
            @Parameter(description = "Sort direction: asc | desc (default: desc)")
                @RequestParam(defaultValue = "desc") String sortDir,
            @Parameter(description = "Zero-based page index (default 0)")
                @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (default 20, max 100)")
                @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        size = Math.min(size, 100);
        log.info("HR checkin list tenantId={} employeeId={} siteId={} status={} page={} size={}",
                tenantId, employeeId, siteId, status, page, size);
        PageResponse<CheckinResponse> result = checkinService.listCheckins(
                tenantId, employeeId, siteId, status, from, to,
                sortBy, sortDir, page, size,
                userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Get employee check-in history",
        description = "Returns a paginated list of the authenticated employee's check-in records, " +
                      "sorted newest first. " +
                      "Optionally filter by date range using 'from' and 'to' (ISO-8601 OffsetDateTime, e.g. 2026-06-01T00:00:00Z). " +
                      "Each record includes the human-readable 'message' field. " +
                      "Requires checkins:read permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Paginated history returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "No employee profile found for this user in the tenant")
    })
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<PageResponse<CheckinResponse>>> getCheckinHistory(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Filter: check-in from (inclusive, ISO-8601)")
                @RequestParam(required = false) OffsetDateTime from,
            @Parameter(description = "Filter: check-in to (inclusive, ISO-8601)")
                @RequestParam(required = false) OffsetDateTime to,
            @Parameter(description = "Zero-based page index (default 0)")
                @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (default 20, max 100)")
                @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        size = Math.min(size, 100);
        log.info("Check-in history tenantId={} userId={} from={} to={} page={} size={}",
                tenantId, userDetails.getUserId(), from, to, page, size);
        PageResponse<CheckinResponse> result =
                checkinService.getCheckinHistory(tenantId, userDetails.getUserId(), from, to, page, size);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Get check-in result",
        description = "Returns the current state of a check-in record owned by the authenticated employee. " +
                      "The response includes a human-readable 'message' field describing the outcome " +
                      "(valid / pending_review / rejected) so the employee knows whether action is required. " +
                      "Returns 403 if the record belongs to a different employee. Requires checkins:read permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Check-in result returned",
            content = @Content(schema = @Schema(implementation = CheckinResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Record belongs to a different employee"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Check-in record not found")
    })
    @GetMapping("/{checkinId}")
    public ResponseEntity<ApiResponse<CheckinResponse>> getCheckinResult(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Check-in record UUID") @PathVariable UUID checkinId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get check-in result tenantId={} checkinId={} userId={}", tenantId, checkinId, userDetails.getUserId());
        CheckinResponse response = checkinService.getCheckinResult(tenantId, checkinId, userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Get check-in detail (HR/Admin)",
        description = "Returns the full evidence record for a check-in including embedded employee, site, and shift context. " +
                      "Intended for HR dispute resolution and attendance auditing. " +
                      "Requires checkins:list permission (platform admins bypass the check). " +
                      "Returns 404 if the record does not exist in this tenant."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Full check-in detail returned",
            content = @Content(schema = @Schema(implementation = CheckinDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing checkins:read permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Check-in record not found")
    })
    @GetMapping("/{checkinId}/detail")
    public ResponseEntity<ApiResponse<CheckinDetailResponse>> getCheckinDetail(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Check-in record UUID") @PathVariable UUID checkinId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("HR checkin detail tenantId={} checkinId={} userId={}", tenantId, checkinId, userDetails.getUserId());
        CheckinDetailResponse response =
                checkinService.getCheckinDetail(tenantId, checkinId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Employee submits explanation for a check-in",
        description = "Allows the authenticated employee to attach a written explanation and optional photo URL " +
                      "to their own check-in record. Commonly used for 'pending_review' check-ins " +
                      "where the GPS was outside the geofence. HR can review this explanation when deciding " +
                      "whether to accept or reject the check-in. " +
                      "Returns 403 if the check-in belongs to a different employee. " +
                      "Requires checkins:read permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Explanation recorded successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error — note is required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Check-in belongs to a different employee"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Check-in not found")
    })
    @PostMapping("/{checkinId}/explain")
    public ResponseEntity<ApiResponse<ExplanationResponse>> explainCheckin(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Check-in record UUID") @PathVariable UUID checkinId,
            @Valid @RequestBody SubmitExplanationRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Employee explain checkin tenantId={} checkinId={} userId={}",
                tenantId, checkinId, userDetails.getUserId());
        ExplanationResponse response =
                checkinService.explainCheckin(tenantId, checkinId, request, userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "HR override check-in status",
        description = "Allows HR/Admin to accept ('valid') or reject ('rejected') a check-in that is in " +
                      "any status. Commonly used to resolve 'pending_review' records where the employee's " +
                      "GPS was outside the geofence but attendance was legitimate. " +
                      "A reason is required and stored on the record for auditing. " +
                      "Triggers attendance summary recomputation for the affected day. " +
                      "Requires checkins:list permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Check-in status updated successfully",
            content = @Content(schema = @Schema(implementation = CheckinResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error, or check-in is already in the requested status"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Missing checkins:list permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Check-in record not found")
    })
    @PatchMapping("/{checkinId}/override")
    public ResponseEntity<ApiResponse<CheckinResponse>> overrideCheckin(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Check-in record UUID") @PathVariable UUID checkinId,
            @Valid @RequestBody OverrideCheckinRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("HR override checkin tenantId={} checkinId={} status={} userId={}",
                tenantId, checkinId, request.getStatus(), userDetails.getUserId());
        CheckinResponse response = checkinService.overrideCheckin(
                tenantId, checkinId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Submit GPS check-out",
        description = "Records a check-out for an open check-in session. " +
                      "The check-in must belong to the authenticated employee and must not have been checked out yet. " +
                      "The checkout GPS point is validated against the site geofence if one is active. " +
                      "Raw work_minutes is computed as the duration between check-in and check-out timestamps " +
                      "(late-checkout capping is applied in task 73). " +
                      "Returns 404 if the check-in record is not found, 403 if it belongs to another employee, " +
                      "409 if already checked out. Requires checkins:create permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Check-out recorded successfully",
            content = @Content(schema = @Schema(implementation = CheckinResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Validation error — missing or invalid fields"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Check-in record belongs to a different employee"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Check-in record not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "Already checked out")
    })
    @PostMapping("/{checkinId}/checkout")
    public ResponseEntity<ApiResponse<CheckinResponse>> submitCheckout(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Check-in record UUID") @PathVariable UUID checkinId,
            @Valid @RequestBody SubmitCheckoutRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Submit check-out tenantId={} checkinId={} userId={}", tenantId, checkinId, userDetails.getUserId());
        CheckinResponse response = checkinService.submitCheckout(tenantId, checkinId, request, userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
