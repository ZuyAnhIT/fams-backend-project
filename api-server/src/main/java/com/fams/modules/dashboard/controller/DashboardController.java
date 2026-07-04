package com.fams.modules.dashboard.controller;

import com.fams.modules.dashboard.dto.response.EmployeeDashboardResponse;
import com.fams.modules.dashboard.dto.response.HrDashboardResponse;
import com.fams.modules.dashboard.dto.response.SupervisorDashboardResponse;
import com.fams.modules.dashboard.service.EmployeeDashboardService;
import com.fams.modules.dashboard.service.HrDashboardService;
import com.fams.modules.dashboard.service.SupervisorDashboardService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@Tag(name = "Dashboard", description = "Role-specific dashboard summaries")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/dashboard")
public class DashboardController {

    private final EmployeeDashboardService employeeDashboardService;
    private final HrDashboardService hrDashboardService;
    private final SupervisorDashboardService supervisorDashboardService;

    public DashboardController(EmployeeDashboardService employeeDashboardService,
                                HrDashboardService hrDashboardService,
                                SupervisorDashboardService supervisorDashboardService) {
        this.employeeDashboardService = employeeDashboardService;
        this.hrDashboardService = hrDashboardService;
        this.supervisorDashboardService = supervisorDashboardService;
    }

    @Operation(
        summary = "Employee dashboard",
        description = "Returns a personalised dashboard for the authenticated employee: " +
                      "today's active assignment(s) with shift timing, the most recent check-in record for today, " +
                      "and aggregated attendance statistics for the current calendar month. " +
                      "Any authenticated tenant member can call this endpoint for their own data. " +
                      "Returns 404 if the caller has no employee profile in this tenant."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Dashboard data returned",
            content = @Content(schema = @Schema(implementation = EmployeeDashboardResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "No valid JWT provided"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "No employee profile found for the caller in this tenant")
    })
    @GetMapping("/employee")
    public ResponseEntity<ApiResponse<EmployeeDashboardResponse>> getEmployeeDashboard(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Employee dashboard tenantId={} userId={}", tenantId, userDetails.getUserId());
        EmployeeDashboardResponse response = employeeDashboardService
                .getDashboard(tenantId, userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "HR/Admin dashboard",
        description = "Returns a tenant-wide dashboard for HR/Admin: personnel headcount, " +
                      "today's attendance snapshot (present, late, on-site now), " +
                      "violation counts with breakdown by type, and site overview. " +
                      "Requires the employees:list permission (tenant_admin and hr_manager). " +
                      "Platform admins bypass the permission check."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "HR dashboard data returned",
            content = @Content(schema = @Schema(implementation = HrDashboardResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "No valid JWT provided"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Caller lacks employees:list permission")
    })
    @GetMapping("/hr")
    public ResponseEntity<ApiResponse<HrDashboardResponse>> getHrDashboard(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("HR dashboard tenantId={} userId={}", tenantId, userDetails.getUserId());
        HrDashboardResponse response = hrDashboardService
                .getDashboard(tenantId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Site Supervisor dashboard",
        description = "Returns per-site presence data for every site where the authenticated employee " +
                      "holds an active supervisor assignment today. For each site: total expected employees, " +
                      "count currently on-site, and the list of on-site employees with check-in time. " +
                      "Returns an empty supervisedSites list if the caller has no supervisor assignments today. " +
                      "Returns 404 if the caller has no employee profile in this tenant."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Supervisor dashboard data returned",
            content = @Content(schema = @Schema(implementation = SupervisorDashboardResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "No valid JWT provided"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "No employee profile found for the caller in this tenant")
    })
    @GetMapping("/supervisor")
    public ResponseEntity<ApiResponse<SupervisorDashboardResponse>> getSupervisorDashboard(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Supervisor dashboard tenantId={} userId={}", tenantId, userDetails.getUserId());
        SupervisorDashboardResponse response = supervisorDashboardService
                .getDashboard(tenantId, userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
