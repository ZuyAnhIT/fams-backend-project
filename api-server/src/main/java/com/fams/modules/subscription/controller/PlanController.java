package com.fams.modules.subscription.controller;

import com.fams.modules.subscription.dto.request.CreatePlanRequest;
import com.fams.modules.subscription.dto.request.UpdatePlanLimitsRequest;
import com.fams.modules.subscription.dto.request.UpdatePlanRequest;
import com.fams.modules.subscription.dto.response.PlanLimitsResponse;
import com.fams.modules.subscription.dto.response.PlanResponse;
import com.fams.modules.subscription.service.PlanLimitsService;
import com.fams.modules.subscription.service.PlanService;
import com.fams.shared.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanService planService;
    private final PlanLimitsService planLimitsService;

    public PlanController(PlanService planService, PlanLimitsService planLimitsService) {
        this.planService = planService;
        this.planLimitsService = planLimitsService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PlanResponse>>> listPlans(
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        log.info("List plans activeOnly={}", activeOnly);
        return ResponseEntity.ok(ApiResponse.success(planService.listPlans(activeOnly)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PlanResponse>> getPlan(@PathVariable UUID id) {
        log.info("Get plan id={}", id);
        return ResponseEntity.ok(ApiResponse.success(planService.getPlan(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<PlanResponse>> createPlan(@Valid @RequestBody CreatePlanRequest request) {
        log.info("Create plan name={}", request.getName());
        return ResponseEntity.status(201).body(ApiResponse.success(planService.createPlan(request)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<PlanResponse>> updatePlan(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePlanRequest request) {
        log.info("Update plan id={}", id);
        return ResponseEntity.ok(ApiResponse.success(planService.updatePlan(id, request)));
    }

    @GetMapping("/{id}/limits")
    public ResponseEntity<ApiResponse<PlanLimitsResponse>> getLimits(@PathVariable UUID id) {
        log.info("Get limits planId={}", id);
        return ResponseEntity.ok(ApiResponse.success(planLimitsService.getLimits(id)));
    }

    @PatchMapping("/{id}/limits")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<PlanLimitsResponse>> updateLimits(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePlanLimitsRequest request) {
        log.info("Update limits planId={}", id);
        return ResponseEntity.ok(ApiResponse.success(planLimitsService.updateLimits(id, request)));
    }
}
