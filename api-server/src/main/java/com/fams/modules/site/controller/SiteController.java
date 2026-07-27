package com.fams.modules.site.controller;

import com.fams.modules.site.dto.request.CreateSiteRequest;
import com.fams.modules.site.dto.request.UpdateSiteRequest;
import com.fams.modules.site.dto.response.SiteDetailResponse;
import com.fams.modules.site.dto.response.SiteResponse;
import com.fams.modules.site.service.SiteService;
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
@Tag(name = "Sites", description = "Construction site / attendance location management within a tenant")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/sites")
public class SiteController {

    private final SiteService siteService;

    public SiteController(SiteService siteService) {
        this.siteService = siteService;
    }

    @Operation(
        summary = "Create site",
        description = "Creates a new construction site / attendance location. " +
                      "Site names are case-insensitively unique per tenant; codes are also unique when provided. " +
                      "Latitude and longitude define the centre point (WGS-84). " +
                      "Geofence polygon is managed separately via the /geofences endpoints. " +
                      "Requires sites:create permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
            description = "Site created",
            content = @Content(schema = @Schema(implementation = SiteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "Site name or code already exists in this tenant")
    })
    @PreAuthorize("hasAuthority('sites:create')")
    @PostMapping
    public ResponseEntity<ApiResponse<SiteResponse>> createSite(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Valid @RequestBody CreateSiteRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Create site tenantId={} by={}", tenantId, userDetails.getUserId());
        SiteResponse response = siteService.createSite(
                tenantId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(
        summary = "List sites (paginated)",
        description = "Returns a paginated list of sites with optional search (name, code, address) " +
                      "and status filter. Supports sorting by name, code, status, timezone, createdAt, updatedAt. " +
                      "Requires sites:list permission. Callable by TENANT_ADMIN, HR_MANAGER, or SITE_SUPERVISOR."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Site list returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant not found")
    })
    @PreAuthorize("hasAuthority('sites:list')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<SiteResponse>>> listSites(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Search by name, code, or address") @RequestParam(required = false) String search,
            @Parameter(description = "Filter by status: active | inactive") @RequestParam(required = false) String status,
            @Parameter(description = "Sort field (name, code, status, timezone, createdAt, updatedAt)") @RequestParam(defaultValue = "name") String sortBy,
            @Parameter(description = "Sort direction: asc | desc") @RequestParam(defaultValue = "asc") String sortDir,
            @Parameter(description = "Page index (0-based)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("List sites tenantId={} by={}", tenantId, userDetails.getUserId());
        PageResponse<SiteResponse> response = siteService.listSites(
                tenantId, search, status, sortBy, sortDir, page, size,
                userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Update site",
        description = "Partially updates a site. All fields are optional — only provided fields are changed. " +
                      "Name and code are case-sensitively unique-checked against other active sites. " +
                      "Set clearCode=true to remove the code. " +
                      "Requires sites:update permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Site updated",
            content = @Content(schema = @Schema(implementation = SiteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant or site not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "Site name or code already exists in this tenant")
    })
    @PreAuthorize("hasAuthority('sites:update')")
    @PutMapping("/{siteId}")
    public ResponseEntity<ApiResponse<SiteResponse>> updateSite(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID") @PathVariable UUID siteId,
            @Valid @RequestBody UpdateSiteRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update site siteId={} tenantId={} by={}", siteId, tenantId, userDetails.getUserId());
        SiteResponse response = siteService.updateSite(
                tenantId, siteId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Get site detail",
        description = "Returns the full detail of a site including its active geofence, shift templates, " +
                      "and active assignment count. `geofence` is null if none has been defined yet; " +
                      "`shifts` is an empty list if no shift templates exist yet for this site. " +
                      "Requires sites:read permission. Callable by TENANT_ADMIN, HR_MANAGER, or SITE_SUPERVISOR."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Site detail returned",
            content = @Content(schema = @Schema(implementation = SiteDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant or site not found")
    })
    @PreAuthorize("hasAuthority('sites:read')")
    @GetMapping("/{siteId}")
    public ResponseEntity<ApiResponse<SiteDetailResponse>> getSite(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID") @PathVariable UUID siteId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get site detail siteId={} tenantId={} by={}", siteId, tenantId, userDetails.getUserId());
        SiteDetailResponse response = siteService.getSiteDetail(
                tenantId, siteId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Delete site",
        description = "Soft-deletes a site. Blocked if it still has active assignments — reassign or end " +
                      "them first. Geofence history and shift templates are retained (orphaned but still " +
                      "queryable for audit) since the site record itself is only soft-deleted. " +
                      "Requires sites:delete permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Site deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Site still has active assignments"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Tenant or site not found")
    })
    @PreAuthorize("hasAuthority('sites:delete')")
    @DeleteMapping("/{siteId}")
    public ResponseEntity<ApiResponse<Void>> deleteSite(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Site UUID") @PathVariable UUID siteId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Delete site siteId={} tenantId={} by={}", siteId, tenantId, userDetails.getUserId());
        siteService.deleteSite(tenantId, siteId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
