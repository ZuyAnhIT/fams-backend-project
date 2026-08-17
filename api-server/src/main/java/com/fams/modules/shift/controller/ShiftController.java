package com.fams.modules.shift.controller;

import com.fams.modules.shift.dto.request.ConfigureShiftOtRequest;
import com.fams.modules.shift.dto.request.CreateShiftRequest;
import com.fams.modules.shift.dto.request.UpdateShiftRequest;
import com.fams.modules.shift.dto.response.ShiftResponse;
import com.fams.modules.shift.service.ShiftService;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@Tag(name = "Shifts", description = "Shift template management per construction site")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/sites/{siteId}/shifts")
public class ShiftController {

    private final ShiftService shiftService;

    public ShiftController(ShiftService shiftService) {
        this.shiftService = shiftService;
    }

    @Operation(
        summary = "Create shift template",
        description = "Creates a new shift template for a site. " +
                      "Shift names are case-insensitively unique per site. " +
                      "For overnight shifts (e.g. 22:00 – 06:00), set allowOvernight=true so the system " +
                      "knows the end time falls on the next calendar day. " +
                      "Requires shifts:create permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
            description = "Shift template created",
            content = @Content(schema = @Schema(implementation = ShiftResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant or site not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "Shift name already exists for this site")
    })
    @PreAuthorize("hasAuthority('shifts:create')")
    @PostMapping
    public ResponseEntity<ApiResponse<ShiftResponse>> createShift(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID")   @PathVariable UUID siteId,
            @Valid @RequestBody CreateShiftRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Create shift siteId={} tenantId={} by={}", siteId, tenantId, userDetails.getUserId());
        ShiftResponse response = shiftService.createShift(
                tenantId, siteId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(
        summary = "List shift templates",
        description = "Returns a paginated list of shift templates for a site, ordered by start time ascending. " +
                      "Optionally filter by status (active | inactive), by isDefault, and/or search by name " +
                      "(case-insensitive substring match). " +
                      "Requires shifts:list permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Shift list returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant or site not found")
    })
    @PreAuthorize("hasAuthority('shifts:list')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ShiftResponse>>> listShifts(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID")   @PathVariable UUID siteId,
            @Parameter(description = "Filter by status: active | inactive") @RequestParam(required = false) String status,
            @Parameter(description = "Search by shift name (case-insensitive substring match)") @RequestParam(required = false) String search,
            @Parameter(description = "Filter by whether the shift is the site's default") @RequestParam(required = false) Boolean isDefault,
            @Parameter(description = "Page index (0-based)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("List shifts siteId={} tenantId={} by={}", siteId, tenantId, userDetails.getUserId());
        PageResponse<ShiftResponse> response = shiftService.listShifts(
                tenantId, siteId, status, search, isDefault, page, size, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Update shift template",
        description = "Partially updates a shift template. All fields are optional — only provided fields are changed. " +
                      "Set status='inactive' to deactivate a shift without deleting it. " +
                      "Name must remain unique (case-insensitive) within the site. " +
                      "Requires shifts:update permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Shift updated",
            content = @Content(schema = @Schema(implementation = ShiftResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant, site, or shift not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "Shift name already exists for this site")
    })
    @PreAuthorize("hasAuthority('shifts:update')")
    @PutMapping("/{shiftId}")
    public ResponseEntity<ApiResponse<ShiftResponse>> updateShift(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID")   @PathVariable UUID siteId,
            @Parameter(description = "Shift UUID")  @PathVariable UUID shiftId,
            @Valid @RequestBody UpdateShiftRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update shift shiftId={} siteId={} tenantId={} by={}", shiftId, siteId, tenantId, userDetails.getUserId());
        ShiftResponse response = shiftService.updateShift(
                tenantId, siteId, shiftId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Configure OT and check-in/out tolerance",
        description = "Partially updates the OT policy for a shift template. " +
                      "All fields are optional — only provided fields are changed. " +
                      "allowOvertime enables overtime tracking for employees on this shift. " +
                      "earlyCheckinMinutes and lateCheckoutMinutes define the tolerance window " +
                      "around the scheduled start and end times. " +
                      "maxOtMinutesPerDay/maxOtMinutesPerWeek (#60, docs/api/backend-feature-audit-2026-08-07.md) " +
                      "set warn-only OT hour caps — exceeding them flags AttendanceSummary.otDailyLimitExceeded/" +
                      "otWeeklyLimitExceeded for HR review but never blocks a checkout or caps otMinutes itself; " +
                      "omit to leave unchanged, or pass clearMaxOtMinutesPerDay/clearMaxOtMinutesPerWeek=true to " +
                      "remove a limit (unlimited). " +
                      "Requires shifts:update permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "OT config updated",
            content = @Content(schema = @Schema(implementation = ShiftResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error or no fields provided"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant, site, or shift not found")
    })
    @PreAuthorize("hasAuthority('shifts:update')")
    @PutMapping("/{shiftId}/ot-config")
    public ResponseEntity<ApiResponse<ShiftResponse>> configureOt(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID")   @PathVariable UUID siteId,
            @Parameter(description = "Shift UUID")  @PathVariable UUID shiftId,
            @Valid @RequestBody ConfigureShiftOtRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Configure OT shiftId={} siteId={} tenantId={} by={}", shiftId, siteId, tenantId, userDetails.getUserId());
        ShiftResponse response = shiftService.configureOt(
                tenantId, siteId, shiftId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Delete shift template",
        description = "Hard-deletes a shift template. Only allowed if the shift has NEVER been referenced " +
                      "by any assignment (active or historical) — otherwise use update with status='inactive' " +
                      "to deactivate it without losing assignment history. " +
                      "Requires shifts:delete permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Shift deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Shift has been used by at least one assignment — deactivate instead"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant, site, or shift not found")
    })
    @PreAuthorize("hasAuthority('shifts:delete')")
    @DeleteMapping("/{shiftId}")
    public ResponseEntity<ApiResponse<Void>> deleteShift(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID")   @PathVariable UUID siteId,
            @Parameter(description = "Shift UUID")  @PathVariable UUID shiftId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Delete shift shiftId={} siteId={} tenantId={} by={}", shiftId, siteId, tenantId, userDetails.getUserId());
        shiftService.deleteShift(tenantId, siteId, shiftId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
