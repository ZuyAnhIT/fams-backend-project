package com.fams.modules.geofence.controller;

import com.fams.modules.geofence.dto.request.CreateGeofenceRequest;
import com.fams.modules.geofence.dto.request.UpdateGeofenceRequest;
import com.fams.modules.geofence.dto.response.GeofenceResponse;
import com.fams.modules.geofence.service.GeofenceService;
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
@Tag(name = "Geofences", description = "Polygon geofence management for construction sites")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/sites/{siteId}/geofences")
public class GeofenceController {

    private final GeofenceService geofenceService;

    public GeofenceController(GeofenceService geofenceService) {
        this.geofenceService = geofenceService;
    }

    @Operation(
        summary = "Create geofence",
        description = "Defines a new polygon boundary for a site. " +
                      "If the site already has an active geofence, it is superseded and retained for history (task 58). " +
                      "Coordinates must be supplied as an ordered list of [longitude, latitude] pairs (GeoJSON order) " +
                      "with at least 4 points where the last point equals the first to close the ring. " +
                      "Requires geofences:create permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
            description = "Geofence created",
            content = @Content(schema = @Schema(implementation = GeofenceResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error — coordinates missing or polygon has fewer than 4 points"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant or site not found")
    })
    @PreAuthorize("hasAuthority('geofences:create')")
    @PostMapping
    public ResponseEntity<ApiResponse<GeofenceResponse>> createGeofence(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID")   @PathVariable UUID siteId,
            @Valid @RequestBody CreateGeofenceRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Create geofence siteId={} tenantId={} by={}", siteId, tenantId, userDetails.getUserId());
        GeofenceResponse response = geofenceService.createGeofence(
                tenantId, siteId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(
        summary = "List geofence history",
        description = "Returns a paginated, newest-first timeline of all geofence versions for a site " +
                      "(both active and superseded). Useful for auditing location boundary disputes. " +
                      "Requires geofences:read permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Geofence history returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant or site not found")
    })
    @PreAuthorize("hasAuthority('geofences:read')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<GeofenceResponse>>> listGeofenceHistory(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID")   @PathVariable UUID siteId,
            @Parameter(description = "Page index (0-based)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("List geofence history siteId={} tenantId={} by={}", siteId, tenantId, userDetails.getUserId());
        PageResponse<GeofenceResponse> response = geofenceService.listGeofenceHistory(
                tenantId, siteId, page, size, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Update active geofence",
        description = "Partially updates the active geofence of a site by creating a new version. " +
                      "The current active geofence is superseded and retained for history (task 58). " +
                      "At least one of coordinates or bufferMeters must be provided. " +
                      "Omitted fields are inherited from the current active version. " +
                      "Returns 404 if no active geofence exists yet. " +
                      "Requires geofences:update permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Geofence updated — new version is now active",
            content = @Content(schema = @Schema(implementation = GeofenceResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error or no fields provided"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant, site, or active geofence not found")
    })
    @PreAuthorize("hasAuthority('geofences:update')")
    @PutMapping("/active")
    public ResponseEntity<ApiResponse<GeofenceResponse>> updateGeofence(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID")   @PathVariable UUID siteId,
            @Valid @RequestBody UpdateGeofenceRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update geofence siteId={} tenantId={} by={}", siteId, tenantId, userDetails.getUserId());
        GeofenceResponse response = geofenceService.updateGeofence(
                tenantId, siteId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Get active geofence",
        description = "Returns the current active geofence for a site. " +
                      "Returns 404 if no geofence has been defined yet. " +
                      "Requires geofences:read permission."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Active geofence returned",
            content = @Content(schema = @Schema(implementation = GeofenceResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant, site, or active geofence not found")
    })
    @PreAuthorize("hasAuthority('geofences:read')")
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<GeofenceResponse>> getActiveGeofence(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID")   @PathVariable UUID siteId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get active geofence siteId={} tenantId={} by={}", siteId, tenantId, userDetails.getUserId());
        GeofenceResponse response = geofenceService.getActiveGeofence(
                tenantId, siteId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
