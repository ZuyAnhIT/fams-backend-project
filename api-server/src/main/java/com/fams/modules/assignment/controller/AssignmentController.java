package com.fams.modules.assignment.controller;

import com.fams.modules.assignment.dto.request.CreateAssignmentRequest;
import com.fams.modules.assignment.dto.request.UpdateAssignmentRequest;
import com.fams.modules.assignment.dto.response.AssignmentResponse;
import com.fams.modules.assignment.service.AssignmentService;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@Slf4j
@Tag(name = "Assignments", description = "Employee-to-site assignment management")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/sites/{siteId}/assignments")
public class AssignmentController {

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @Operation(summary = "List assignments",
        description = "Returns paginated assignments for a site. Filter by status, role, employeeId, shiftId, " +
                      "and/or a date range (dateRangeFrom/dateRangeTo — an overlap test: an assignment matches " +
                      "if it is active on any day within the given range, not just an exact-match range). " +
                      "Sort by startDate, endDate, role, status, createdAt. Requires assignments:list permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Paginated list of assignments"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant or site not found")
    })
    @PreAuthorize("hasAuthority('assignments:list')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AssignmentResponse>>> listAssignments(
            @PathVariable UUID tenantId,
            @PathVariable UUID siteId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false) UUID shiftId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateRangeFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateRangeTo,
            @RequestParam(defaultValue = "startDate") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        PageResponse<AssignmentResponse> result = assignmentService.listAssignments(
                tenantId, siteId, status, role, employeeId, shiftId, dateRangeFrom, dateRangeTo,
                sortBy, sortDir, page, size,
                userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(summary = "Update assignment",
        description = "Partially updates an assignment — shift, dates, role, or notes. " +
                      "Set clearShift=true to unlink the shift. Set clearEndDate=true to make open-ended. " +
                      "A CANCELLED assignment cannot be modified — it is a closed record kept for history; " +
                      "cancelling is final. " +
                      "Requires assignments:update permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Assignment updated",
            content = @Content(schema = @Schema(implementation = AssignmentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error, endDate before startDate, or assignment is cancelled"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant, site, assignment, or shift not found")
    })
    @PreAuthorize("hasAuthority('assignments:update')")
    @PutMapping("/{assignmentId}")
    public ResponseEntity<ApiResponse<AssignmentResponse>> updateAssignment(
            @PathVariable UUID tenantId,
            @PathVariable UUID siteId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody UpdateAssignmentRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        AssignmentResponse response = assignmentService.updateAssignment(
                tenantId, siteId, assignmentId, request,
                userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Cancel assignment",
        description = "Sets the assignment status to 'cancelled'. Cancelled assignments are retained for audit. " +
                      "Requires assignments:delete permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204",
            description = "Assignment cancelled"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Assignment is already cancelled"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant, site, or assignment not found")
    })
    @PreAuthorize("hasAuthority('assignments:delete')")
    @DeleteMapping("/{assignmentId}")
    public ResponseEntity<Void> cancelAssignment(
            @PathVariable UUID tenantId,
            @PathVariable UUID siteId,
            @PathVariable UUID assignmentId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        assignmentService.cancelAssignment(
                tenantId, siteId, assignmentId,
                userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "Create assignment",
        description = "Assigns an employee to a site for a given shift and date range. " +
                      "An employee may have more than one active assignment at the same site as long as their " +
                      "actual shift-hour windows don't overlap (e.g. pre-creating a future assignment while the " +
                      "current one is still active) — overlapping hours at the same site, or at a different " +
                      "site, are rejected with 409. " +
                      "shiftId is optional; omit it for a site-only assignment without a fixed shift. " +
                      "endDate is optional for open-ended assignments. " +
                      "Requires assignments:create permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
            description = "Assignment created",
            content = @Content(schema = @Schema(implementation = AssignmentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error or endDate before startDate"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant, site, employee, or shift not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "Employee already has an overlapping active assignment (same site or a different site) during this period")
    })
    @PreAuthorize("hasAuthority('assignments:create')")
    @PostMapping
    public ResponseEntity<ApiResponse<AssignmentResponse>> createAssignment(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID")   @PathVariable UUID siteId,
            @Valid @RequestBody CreateAssignmentRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Create assignment employeeId={} siteId={} tenantId={} by={}",
                request.getEmployeeId(), siteId, tenantId, userDetails.getUserId());
        AssignmentResponse response = assignmentService.createAssignment(
                tenantId, siteId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }
}
