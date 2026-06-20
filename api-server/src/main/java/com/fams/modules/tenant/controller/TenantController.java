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
import com.fams.modules.tenant.dto.response.TenantResponse;
import com.fams.modules.tenant.dto.response.TenantSettingsResponse;
import com.fams.modules.tenant.service.IpWhitelistService;
import com.fams.modules.tenant.service.TenantService;
import com.fams.modules.tenant.service.TenantSettingsService;

import java.util.List;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
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
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;
    private final TenantSettingsService tenantSettingsService;
    private final IpWhitelistService ipWhitelistService;
    private final TenantSubscriptionService subscriptionService;

    public TenantController(TenantService tenantService, TenantSettingsService tenantSettingsService,
                            IpWhitelistService ipWhitelistService,
                            TenantSubscriptionService subscriptionService) {
        this.tenantService = tenantService;
        this.tenantSettingsService = tenantSettingsService;
        this.ipWhitelistService = ipWhitelistService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<TenantResponse>>> listTenants(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String countryCode,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.info("List tenants: search={} status={} page={} size={}", search, status, page, size);
        PageResponse<TenantResponse> result = tenantService.listTenants(
                search, status, industry, countryCode, sortBy, sortDir, page, size);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<TenantResponse>> createTenant(
            @Valid @RequestBody CreateTenantRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Create tenant request: slug={} by userId={}", request.getSlug(), userDetails.getUserId());
        TenantResponse response = tenantService.createTenant(request, userDetails.getUserId());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<TenantResponse>> updateTenant(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update tenant id={} by userId={}", id, userDetails.getUserId());
        TenantResponse response = tenantService.updateTenant(id, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}/settings")
    public ResponseEntity<ApiResponse<TenantSettingsResponse>> getSettings(
            @PathVariable UUID id,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get settings tenantId={} by userId={}", id, userDetails.getUserId());
        TenantSettingsResponse response = tenantSettingsService.getSettings(id, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{id}/settings")
    public ResponseEntity<ApiResponse<TenantSettingsResponse>> updateSettings(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantSettingsRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update settings tenantId={} by userId={}", id, userDetails.getUserId());
        TenantSettingsResponse response = tenantSettingsService.updateSettings(id, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}/ip-whitelists")
    public ResponseEntity<ApiResponse<List<IpWhitelistResponse>>> listIpWhitelists(
            @PathVariable UUID id,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("List IP whitelists tenantId={} by userId={}", id, userDetails.getUserId());
        List<IpWhitelistResponse> result = ipWhitelistService.listEntries(id, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{id}/ip-whitelists")
    public ResponseEntity<ApiResponse<IpWhitelistResponse>> addIpWhitelist(
            @PathVariable UUID id,
            @Valid @RequestBody CreateIpWhitelistRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Add IP whitelist tenantId={} ip={} by userId={}", id, request.getIpAddress(), userDetails.getUserId());
        IpWhitelistResponse response = ipWhitelistService.addEntry(id, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @PatchMapping("/{id}/ip-whitelists/{entryId}")
    public ResponseEntity<ApiResponse<IpWhitelistResponse>> updateIpWhitelist(
            @PathVariable UUID id,
            @PathVariable UUID entryId,
            @Valid @RequestBody UpdateIpWhitelistRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update IP whitelist entryId={} tenantId={} by userId={}", entryId, id, userDetails.getUserId());
        IpWhitelistResponse response = ipWhitelistService.updateEntry(id, entryId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/{id}/ip-whitelists/{entryId}")
    public ResponseEntity<ApiResponse<Void>> deleteIpWhitelist(
            @PathVariable UUID id,
            @PathVariable UUID entryId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Delete IP whitelist entryId={} tenantId={} by userId={}", entryId, id, userDetails.getUserId());
        ipWhitelistService.deleteEntry(id, entryId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/{id}/subscription")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> getSubscription(
            @PathVariable UUID id,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get subscription tenantId={} by userId={}", id, userDetails.getUserId());
        SubscriptionResponse response = subscriptionService.getSubscription(id, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{id}/subscription")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> assignSubscription(
            @PathVariable UUID id,
            @Valid @RequestBody AssignSubscriptionRequest request) {
        log.info("Assign subscription tenantId={} planId={}", id, request.getPlanId());
        SubscriptionResponse response = subscriptionService.assignSubscription(id, request);
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @PatchMapping("/{id}/subscription")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> updateSubscription(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSubscriptionRequest request) {
        log.info("Update subscription tenantId={}", id);
        SubscriptionResponse response = subscriptionService.updateSubscription(id, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
