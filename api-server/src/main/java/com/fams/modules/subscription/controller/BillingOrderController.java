package com.fams.modules.subscription.controller;

import com.fams.modules.subscription.dto.request.CancelBillingOrderRequest;
import com.fams.modules.subscription.dto.request.CreateBillingOrderRequest;
import com.fams.modules.subscription.dto.response.BillingOrderResponse;
import com.fams.modules.subscription.entity.BillingOrder.BillingOrderStatus;
import com.fams.modules.subscription.service.BillingOrderService;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Billing", description = "Company checkout and platform payment-order operations")
@RestController
public class BillingOrderController {

    private final BillingOrderService billingOrderService;

    public BillingOrderController(BillingOrderService billingOrderService) {
        this.billingOrderService = billingOrderService;
    }

    @Operation(summary = "Create a payOS checkout for a subscription plan")
    @PostMapping("/api/v1/tenants/{tenantId}/billing-orders")
    public ResponseEntity<ApiResponse<BillingOrderResponse>> create(
            @PathVariable UUID tenantId,
            @Valid @RequestBody CreateBillingOrderRequest request,
            @AuthenticationPrincipal FamsUserDetails user) {
        return ResponseEntity.status(201).body(ApiResponse.success(
                billingOrderService.createOrder(tenantId, request, user.getUserId(), user.isPlatformAdmin())));
    }

    @Operation(summary = "List the company's billing history")
    @GetMapping("/api/v1/tenants/{tenantId}/billing-orders")
    public ResponseEntity<ApiResponse<PageResponse<BillingOrderResponse>>> listTenantOrders(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal FamsUserDetails user) {
        return ResponseEntity.ok(ApiResponse.success(billingOrderService.listTenantOrders(
                tenantId, user.getUserId(), user.isPlatformAdmin(), page, size)));
    }

    @Operation(summary = "Get one company billing order")
    @GetMapping("/api/v1/tenants/{tenantId}/billing-orders/{orderId}")
    public ResponseEntity<ApiResponse<BillingOrderResponse>> getTenantOrder(
            @PathVariable UUID tenantId, @PathVariable UUID orderId,
            @AuthenticationPrincipal FamsUserDetails user) {
        return ResponseEntity.ok(ApiResponse.success(billingOrderService.getTenantOrder(
                tenantId, orderId, user.getUserId(), user.isPlatformAdmin())));
    }

    @Operation(summary = "Cancel an unfinished company billing order")
    @PostMapping("/api/v1/tenants/{tenantId}/billing-orders/{orderId}/cancel")
    public ResponseEntity<ApiResponse<BillingOrderResponse>> cancelTenantOrder(
            @PathVariable UUID tenantId, @PathVariable UUID orderId,
            @Valid @RequestBody(required = false) CancelBillingOrderRequest request,
            @AuthenticationPrincipal FamsUserDetails user) {
        return ResponseEntity.ok(ApiResponse.success(billingOrderService.cancelTenantOrder(
                tenantId, orderId, request, user.getUserId(), user.isPlatformAdmin())));
    }

    @Operation(summary = "Refresh a company billing order from payOS")
    @PostMapping("/api/v1/tenants/{tenantId}/billing-orders/{orderId}/refresh")
    public ResponseEntity<ApiResponse<BillingOrderResponse>> refreshTenantOrder(
            @PathVariable UUID tenantId, @PathVariable UUID orderId,
            @AuthenticationPrincipal FamsUserDetails user) {
        // The scoped read first enforces owner/tenant access before the provider call.
        billingOrderService.getTenantOrder(tenantId, orderId, user.getUserId(), user.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(billingOrderService.refreshOrder(orderId, user.getUserId())));
    }

    @Operation(summary = "List billing orders across the platform")
    @GetMapping("/api/v1/billing-orders")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('billing:list')")
    public ResponseEntity<ApiResponse<PageResponse<BillingOrderResponse>>> listPlatformOrders(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) BillingOrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(ApiResponse.success(
                billingOrderService.listPlatformOrders(tenantId, status, page, size)));
    }

    @Operation(summary = "Get a billing order for platform operations")
    @GetMapping("/api/v1/billing-orders/{orderId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('billing:read')")
    public ResponseEntity<ApiResponse<BillingOrderResponse>> getPlatformOrder(@PathVariable UUID orderId) {
        return ResponseEntity.ok(ApiResponse.success(billingOrderService.getPlatformOrder(orderId)));
    }

    @Operation(summary = "Reconcile a billing order with payOS")
    @PostMapping("/api/v1/billing-orders/{orderId}/refresh")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('billing:update')")
    public ResponseEntity<ApiResponse<BillingOrderResponse>> refreshPlatformOrder(
            @PathVariable UUID orderId, @AuthenticationPrincipal FamsUserDetails user) {
        return ResponseEntity.ok(ApiResponse.success(billingOrderService.refreshOrder(orderId, user.getUserId())));
    }

    @Operation(summary = "Cancel an unfinished billing order as Billing Ops")
    @PostMapping("/api/v1/billing-orders/{orderId}/cancel")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasAuthority('billing:update')")
    public ResponseEntity<ApiResponse<BillingOrderResponse>> cancelPlatformOrder(
            @PathVariable UUID orderId,
            @Valid @RequestBody(required = false) CancelBillingOrderRequest request,
            @AuthenticationPrincipal FamsUserDetails user) {
        return ResponseEntity.ok(ApiResponse.success(
                billingOrderService.cancelPlatformOrder(orderId, request, user.getUserId())));
    }
}
