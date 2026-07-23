package com.fams.modules.subscription.controller;

import com.fams.modules.subscription.dto.request.CreatePlanRequest;
import com.fams.modules.subscription.dto.request.UpdatePlanLimitsRequest;
import com.fams.modules.subscription.dto.request.UpdatePlanRequest;
import com.fams.modules.subscription.dto.response.PlanLimitsResponse;
import com.fams.modules.subscription.dto.response.PlanResponse;
import com.fams.modules.subscription.service.PlanLimitsService;
import com.fams.modules.subscription.service.PlanService;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.response.ApiResponse;
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
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@Tag(name = "Plans", description = "SaaS subscription plan management — create, update, and configure plan limits")
@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanService planService;
    private final PlanLimitsService planLimitsService;

    public PlanController(PlanService planService, PlanLimitsService planLimitsService) {
        this.planService = planService;
        this.planLimitsService = planLimitsService;
    }

    @Operation(summary = "List subscription plans",
        description = "Returns a paginated list of plans. Pass activeOnly=true to filter out inactive plans. Accessible without authentication.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Plans returned",
            content = @Content(schema = @Schema(implementation = PlanResponse.class)))
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<PlanResponse>>> listPlans(
            @Parameter(description = "When true, only active plans are returned") @RequestParam(defaultValue = "false") boolean activeOnly,
            @Parameter(description = "Sort field (sortOrder, name, displayName, priceMonthly, priceYearly, createdAt, updatedAt)") @RequestParam(defaultValue = "sortOrder") String sortBy,
            @Parameter(description = "Sort direction: asc or desc") @RequestParam(defaultValue = "asc") String sortDir,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (1–100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.info("List plans activeOnly={} page={} size={}", activeOnly, page, size);
        return ResponseEntity.ok(ApiResponse.success(planService.listPlans(activeOnly, sortBy, sortDir, page, size)));
    }

    @Operation(summary = "Get a plan by ID",
        description = "Returns a single plan's details. Accessible without authentication.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Plan found",
            content = @Content(schema = @Schema(implementation = PlanResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Plan not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PlanResponse>> getPlan(
            @Parameter(description = "Plan UUID") @PathVariable UUID id) {
        log.info("Get plan id={}", id);
        return ResponseEntity.ok(ApiResponse.success(planService.getPlan(id)));
    }

    @Operation(summary = "Create a subscription plan",
        description = "Creates a new SaaS plan. Restricted to Platform Admins.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Plan created",
            content = @Content(schema = @Schema(implementation = PlanResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Plan name already exists")
    })
    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<PlanResponse>> createPlan(@Valid @RequestBody CreatePlanRequest request) {
        log.info("Create plan name={}", request.getName());
        return ResponseEntity.status(201).body(ApiResponse.success(planService.createPlan(request)));
    }

    @Operation(summary = "Update a subscription plan",
        description = "Updates display name, description, prices, sort order, or active status of a plan. Restricted to Platform Admins. " +
            "Issue #8 (docs/issues/ISSUES.md): setting isActive=false while tenants are still subscribed requires migrateToPlanId — " +
            "they are migrated to that plan first (aborting entirely if any tenant's current usage wouldn't fit its limits) rather than being silently stranded on a deactivated plan.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Plan updated",
            content = @Content(schema = @Schema(implementation = PlanResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Plan not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Deactivation blocked: tenants still subscribed, no/invalid migrateToPlanId, or migration would exceed the target plan's limits")
    })
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<PlanResponse>> updatePlan(
            @Parameter(description = "Plan UUID") @PathVariable UUID id,
            @Valid @RequestBody UpdatePlanRequest request) {
        log.info("Update plan id={}", id);
        return ResponseEntity.ok(ApiResponse.success(planService.updatePlan(id, request)));
    }

    @Operation(summary = "Get plan limits",
        description = "Returns resource limits (max employees, sites, storage, random checks) for the plan. Accessible without authentication.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Limits returned",
            content = @Content(schema = @Schema(implementation = PlanLimitsResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Plan not found")
    })
    @GetMapping("/{id}/limits")
    public ResponseEntity<ApiResponse<PlanLimitsResponse>> getLimits(
            @Parameter(description = "Plan UUID") @PathVariable UUID id) {
        log.info("Get limits planId={}", id);
        return ResponseEntity.ok(ApiResponse.success(planLimitsService.getLimits(id)));
    }

    @Operation(summary = "Update plan limits",
        description = "Sets resource limits for a plan. Pass null (or set clearXxx=true) to indicate unlimited. Restricted to Platform Admins.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Limits updated",
            content = @Content(schema = @Schema(implementation = PlanLimitsResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Platform Admin role required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Plan not found")
    })
    @PatchMapping("/{id}/limits")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<PlanLimitsResponse>> updateLimits(
            @Parameter(description = "Plan UUID") @PathVariable UUID id,
            @Valid @RequestBody UpdatePlanLimitsRequest request) {
        log.info("Update limits planId={}", id);
        return ResponseEntity.ok(ApiResponse.success(planLimitsService.updateLimits(id, request)));
    }
}
