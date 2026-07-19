package com.fams.modules.employee.controller;

import com.fams.modules.employee.dto.request.CreateDepartmentRequest;
import com.fams.modules.employee.dto.request.UpdateDepartmentRequest;
import com.fams.modules.employee.dto.response.DepartmentResponse;
import com.fams.modules.employee.service.DepartmentService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "Departments", description = "Managed department CRUD per tenant")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @Operation(summary = "List departments",
        description = "Returns all active departments for the tenant. Requires employees:read or employees:list permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Departments returned",
            content = @Content(schema = @Schema(implementation = DepartmentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<List<DepartmentResponse>>> listDepartments(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("List departments tenantId={} by userId={}", tenantId, userDetails.getUserId());
        List<DepartmentResponse> result = departmentService.listDepartments(
                tenantId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(summary = "Create department",
        description = "Creates a new department for the tenant. Requires employees:create permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Department created",
            content = @Content(schema = @Schema(implementation = DepartmentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Department name already exists in this tenant")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<DepartmentResponse>> createDepartment(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Valid @RequestBody CreateDepartmentRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Create department tenantId={} name={} by userId={}", tenantId, request.getName(), userDetails.getUserId());
        DepartmentResponse response = departmentService.createDepartment(
                tenantId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(summary = "Update department",
        description = "Updates name and/or description of an existing department. Requires employees:update permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Department updated",
            content = @Content(schema = @Schema(implementation = DepartmentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant or department not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Department name already exists in this tenant")
    })
    @PatchMapping("/{departmentId}")
    public ResponseEntity<ApiResponse<DepartmentResponse>> updateDepartment(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Department UUID") @PathVariable UUID departmentId,
            @Valid @RequestBody UpdateDepartmentRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update department id={} tenantId={} by userId={}", departmentId, tenantId, userDetails.getUserId());
        DepartmentResponse response = departmentService.updateDepartment(
                tenantId, departmentId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Delete department",
        description = "Soft-deletes a department (sets deleted_at). Requires employees:delete permission.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Department deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant or department not found")
    })
    @DeleteMapping("/{departmentId}")
    public ResponseEntity<ApiResponse<Void>> deleteDepartment(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Department UUID") @PathVariable UUID departmentId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Delete department id={} tenantId={} by userId={}", departmentId, tenantId, userDetails.getUserId());
        departmentService.deleteDepartment(tenantId, departmentId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
