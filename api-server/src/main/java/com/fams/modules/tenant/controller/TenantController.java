package com.fams.modules.tenant.controller;

import com.fams.modules.subscription.dto.request.AssignSubscriptionRequest;
import com.fams.modules.subscription.dto.request.UpdateSubscriptionRequest;
import com.fams.modules.subscription.dto.response.SubscriptionResponse;
import com.fams.modules.subscription.service.TenantSubscriptionService;
import com.fams.modules.tenant.dto.request.CreateIpWhitelistRequest;
import com.fams.modules.tenant.dto.request.CreateTenantRequest;
import com.fams.modules.tenant.dto.request.UpdateIpWhitelistRequest;
import com.fams.modules.tenant.dto.request.UpdateTenantRequest;
import com.fams.modules.tenant.dto.request.UpdateTenantSettingsRequest;
import com.fams.modules.tenant.dto.response.IpWhitelistResponse;
import com.fams.modules.tenant.dto.response.TenantDetailResponse;
import com.fams.modules.tenant.dto.response.TenantResponse;
import com.fams.modules.tenant.dto.response.TenantSettingsResponse;
import com.fams.modules.tenant.service.IpWhitelistService;
import com.fams.modules.tenant.service.TenantDetailService;
import com.fams.modules.tenant.service.TenantService;
import com.fams.modules.tenant.service.TenantSettingsService;

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

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Tenants", description = "Tenant management, settings, IP whitelist, and subscription assignment")
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;
    private final TenantDetailService tenantDetailService;
    private final TenantSettingsService tenantSettingsService;
    private final IpWhitelistService ipWhitelistService;
    private final TenantSubscriptionService subscriptionService;

    public TenantController(TenantService tenantService,
                            TenantDetailService tenantDetailService,
                            TenantSettingsService tenantSettingsService,
                            IpWhitelistService ipWhitelistService,
                            TenantSubscriptionService subscriptionService) {
        this.tenantService = tenantService;
        this.tenantDetailService = tenantDetailService;
        this.tenantSettingsService = tenantSettingsService;
        this.ipWhitelistService = ipWhitelistService;
        this.subscriptionService = subscriptionService;
    }

    @Operation(summary = "List tenants",
        description = "Returns a paginated, searchable, filterable list of tenants. Restricted to Platform Admins.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tenants returned",
            content = @Content(schema = @Schema(implementation = TenantResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required")
    })
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<TenantResponse>>> listTenants(
            @Parameter(description = "Search by name or slug") @RequestParam(required = false) String search,
            @Parameter(description = "Filter by status") @RequestParam(required = false) String status,
            @Parameter(description = "Filter by industry") @RequestParam(required = false) String industry,
            @Parameter(description = "Filter by ISO-3166-1 alpha-2 country code") @RequestParam(required = false) String countryCode,
            @Parameter(description = "Sort field") @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter(description = "Sort direction: asc or desc") @RequestParam(defaultValue = "desc") String sortDir,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (1–100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.info("List tenants: search={} status={} page={} size={}", search, status, page, size);
        PageResponse<TenantResponse> result = tenantService.listTenants(
                search, status, industry, countryCode, sortBy, sortDir, page, size);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(summary = "Get tenant operational detail",
        description = "Returns the full operational view of a tenant: profile, subscription, plan limits, and current usage counts. Restricted to Platform Admins.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Detail returned",
            content = @Content(schema = @Schema(implementation = TenantDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @GetMapping("/{id}/detail")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<TenantDetailResponse>> getTenantDetail(
            @Parameter(description = "Tenant UUID") @PathVariable UUID id,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get tenant detail id={} by userId={}", id, userDetails.getUserId());
        TenantDetailResponse response = tenantDetailService.getDetail(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Create a tenant",
        description = "Provisions a new company workspace. Restricted to Platform Admins.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Tenant created",
            content = @Content(schema = @Schema(implementation = TenantResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Slug already taken")
    })
    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<TenantResponse>> createTenant(
            @Valid @RequestBody CreateTenantRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Create tenant request: slug={} by userId={}", request.getSlug(), userDetails.getUserId());
        TenantResponse response = tenantService.createTenant(request, userDetails.getUserId());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(summary = "Update tenant profile",
        description = "Updates name, domain, logo, industry, timezone, locale, and currency for a tenant. Callable by the tenant's Company Admin or a Platform Admin.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tenant updated",
            content = @Content(schema = @Schema(implementation = TenantResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<TenantResponse>> updateTenant(
            @Parameter(description = "Tenant UUID") @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update tenant id={} by userId={}", id, userDetails.getUserId());
        TenantResponse response = tenantService.updateTenant(id, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Get tenant UI settings",
        description = "Returns display/branding settings (date format, time format, brand colors) for the tenant. Callable by the tenant's admin or a Platform Admin.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Settings returned",
            content = @Content(schema = @Schema(implementation = TenantSettingsResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @GetMapping("/{id}/settings")
    public ResponseEntity<ApiResponse<TenantSettingsResponse>> getSettings(
            @Parameter(description = "Tenant UUID") @PathVariable UUID id,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get settings tenantId={} by userId={}", id, userDetails.getUserId());
        TenantSettingsResponse response = tenantSettingsService.getSettings(id, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Update tenant UI settings",
        description = "Updates date format, time format, and brand colors for the tenant. Callable by the tenant's admin or a Platform Admin.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Settings updated",
            content = @Content(schema = @Schema(implementation = TenantSettingsResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @PatchMapping("/{id}/settings")
    public ResponseEntity<ApiResponse<TenantSettingsResponse>> updateSettings(
            @Parameter(description = "Tenant UUID") @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantSettingsRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update settings tenantId={} by userId={}", id, userDetails.getUserId());
        TenantSettingsResponse response = tenantSettingsService.updateSettings(id, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "List IP whitelist entries",
        description = "Returns a paginated list of IP whitelist entries for a tenant. Callable by the tenant's admin or a Platform Admin.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "IP whitelist returned",
            content = @Content(schema = @Schema(implementation = IpWhitelistResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @GetMapping("/{id}/ip-whitelists")
    public ResponseEntity<ApiResponse<PageResponse<IpWhitelistResponse>>> listIpWhitelists(
            @Parameter(description = "Tenant UUID") @PathVariable UUID id,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (1–100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("List IP whitelists tenantId={} page={} size={} by userId={}", id, page, size, userDetails.getUserId());
        PageResponse<IpWhitelistResponse> result = ipWhitelistService.listEntries(id, userDetails.getUserId(), userDetails.isPlatformAdmin(), page, size);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(summary = "Add an IP whitelist entry",
        description = "Adds an IPv4/IPv6/CIDR entry to the tenant's IP whitelist. Callable by the tenant's admin or a Platform Admin.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Entry added",
            content = @Content(schema = @Schema(implementation = IpWhitelistResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "IP/CIDR already exists for this tenant")
    })
    @PostMapping("/{id}/ip-whitelists")
    public ResponseEntity<ApiResponse<IpWhitelistResponse>> addIpWhitelist(
            @Parameter(description = "Tenant UUID") @PathVariable UUID id,
            @Valid @RequestBody CreateIpWhitelistRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Add IP whitelist tenantId={} ip={} by userId={}", id, request.getIpAddress(), userDetails.getUserId());
        IpWhitelistResponse response = ipWhitelistService.addEntry(id, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(summary = "Update an IP whitelist entry",
        description = "Updates the label, scope, or active status of an existing IP whitelist entry. Callable by the tenant's admin or a Platform Admin.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Entry updated",
            content = @Content(schema = @Schema(implementation = IpWhitelistResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant or entry not found")
    })
    @PatchMapping("/{id}/ip-whitelists/{entryId}")
    public ResponseEntity<ApiResponse<IpWhitelistResponse>> updateIpWhitelist(
            @Parameter(description = "Tenant UUID") @PathVariable UUID id,
            @Parameter(description = "IP whitelist entry UUID") @PathVariable UUID entryId,
            @Valid @RequestBody UpdateIpWhitelistRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update IP whitelist entryId={} tenantId={} by userId={}", entryId, id, userDetails.getUserId());
        IpWhitelistResponse response = ipWhitelistService.updateEntry(id, entryId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Delete an IP whitelist entry",
        description = "Permanently removes an IP whitelist entry from the tenant. Callable by the tenant's admin or a Platform Admin.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Entry deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant or entry not found")
    })
    @DeleteMapping("/{id}/ip-whitelists/{entryId}")
    public ResponseEntity<ApiResponse<Void>> deleteIpWhitelist(
            @Parameter(description = "Tenant UUID") @PathVariable UUID id,
            @Parameter(description = "IP whitelist entry UUID") @PathVariable UUID entryId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Delete IP whitelist entryId={} tenantId={} by userId={}", entryId, id, userDetails.getUserId());
        ipWhitelistService.deleteEntry(id, entryId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "Get tenant subscription",
        description = "Returns the active subscription for the tenant including plan name, billing cycle, and expiry. Callable by the tenant's admin or a Platform Admin.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription returned",
            content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant or subscription not found")
    })
    @GetMapping("/{id}/subscription")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> getSubscription(
            @Parameter(description = "Tenant UUID") @PathVariable UUID id,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get subscription tenantId={} by userId={}", id, userDetails.getUserId());
        SubscriptionResponse response = subscriptionService.getSubscription(id, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Assign subscription to tenant",
        description = "Creates a new subscription entry linking the tenant to a plan. Restricted to Platform Admins.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Subscription assigned",
            content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant or plan not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Tenant already has an active subscription")
    })
    @PostMapping("/{id}/subscription")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> assignSubscription(
            @Parameter(description = "Tenant UUID") @PathVariable UUID id,
            @Valid @RequestBody AssignSubscriptionRequest request) {
        log.info("Assign subscription tenantId={} planId={}", id, request.getPlanId());
        SubscriptionResponse response = subscriptionService.assignSubscription(id, request);
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(summary = "Suspend a tenant",
        description = "Sets tenant status to 'suspended', immediately blocking all non-admin users. Restricted to Platform Admins.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tenant suspended",
            content = @Content(schema = @Schema(implementation = TenantResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Tenant is already suspended or cancelled"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<TenantResponse>> suspendTenant(
            @Parameter(description = "Tenant UUID") @PathVariable UUID id,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Suspend tenant id={} by userId={}", id, userDetails.getUserId());
        TenantResponse response = tenantService.suspendTenant(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Reactivate a tenant",
        description = "Sets tenant status from 'suspended' back to 'active'. Restricted to Platform Admins.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tenant reactivated",
            content = @Content(schema = @Schema(implementation = TenantResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Tenant is not currently suspended"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<TenantResponse>> reactivateTenant(
            @Parameter(description = "Tenant UUID") @PathVariable UUID id,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Reactivate tenant id={} by userId={}", id, userDetails.getUserId());
        TenantResponse response = tenantService.reactivateTenant(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Cancel a tenant",
        description = "Permanently cancels a tenant, blocking all access. Cannot be undone. Restricted to Platform Admins.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tenant cancelled",
            content = @Content(schema = @Schema(implementation = TenantResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Tenant is already cancelled"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<TenantResponse>> cancelTenant(
            @Parameter(description = "Tenant UUID") @PathVariable UUID id,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Cancel tenant id={} by userId={}", id, userDetails.getUserId());
        TenantResponse response = tenantService.cancelTenant(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Update tenant subscription",
        description = "Changes the plan, billing cycle, status, or expiry of an existing subscription. Restricted to Platform Admins.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription updated",
            content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant or subscription not found")
    })
    @PatchMapping("/{id}/subscription")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> updateSubscription(
            @Parameter(description = "Tenant UUID") @PathVariable UUID id,
            @Valid @RequestBody UpdateSubscriptionRequest request) {
        log.info("Update subscription tenantId={}", id);
        SubscriptionResponse response = subscriptionService.updateSubscription(id, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
