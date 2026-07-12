package com.fams.modules.employee.controller;

import com.fams.modules.employee.dto.request.ChangeEmployeeStatusRequest;
import com.fams.modules.employee.dto.request.CreateEmployeeRequest;
import com.fams.modules.employee.dto.request.UpdateEmployeeRequest;
import com.fams.modules.employee.dto.response.EmployeeDetailResponse;
import com.fams.modules.employee.dto.response.EmployeeImportResponse;
import com.fams.modules.employee.dto.response.EmployeeResponse;
import com.fams.modules.employee.service.EmployeeExportService;
import com.fams.modules.employee.service.EmployeeService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Slf4j
@Tag(name = "Employees", description = "Employee profile management within a tenant")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/employees")
public class EmployeeController {

    private final EmployeeService employeeService;
    private final EmployeeExportService employeeExportService;

    public EmployeeController(EmployeeService employeeService, EmployeeExportService employeeExportService) {
        this.employeeService = employeeService;
        this.employeeExportService = employeeExportService;
    }

    @Operation(
        summary = "Create employee manually",
        description = "Creates an employee HR profile directly without an email invitation. " +
                      "Useful for bulk onboarding or employees without email accounts. " +
                      "The employee is not linked to an auth user account until they accept an invitation or are matched manually. " +
                      "Requires employees:create permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Employee created",
            content = @Content(schema = @Schema(implementation = EmployeeResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Employee code already exists in this tenant")
    })
    @PreAuthorize("hasAuthority('employees:create')")
    @PostMapping
    public ResponseEntity<ApiResponse<EmployeeResponse>> createEmployee(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Valid @RequestBody CreateEmployeeRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Create employee tenantId={} by={}", tenantId, userDetails.getUserId());
        EmployeeResponse response = employeeService.createEmployee(
                tenantId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.status(201).body(ApiResponse.success(response));
    }

    @Operation(
        summary = "Import employees from Excel",
        description = "Imports employee HR profiles from an .xlsx file. " +
                      "Row 1 must be a header with columns (case-insensitive): " +
                      "firstName, lastName, email, phone, employeeCode, position, department, hiredDate. " +
                      "Rows with validation errors are skipped and reported; valid rows are created. " +
                      "Requires employees:create permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Import completed (check successCount/failedCount)",
            content = @Content(schema = @Schema(implementation = EmployeeImportResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid or unreadable file"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @PreAuthorize("hasAuthority('employees:create')")
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<EmployeeImportResponse>> importEmployees(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Excel file (.xlsx)") @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Import employees tenantId={} file={} by={}", tenantId, file.getOriginalFilename(), userDetails.getUserId());
        EmployeeImportResponse response = employeeService.importEmployees(
                tenantId, file, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "List employees",
        description = "Returns a paginated, searchable, filterable list of employees within a tenant. " +
                      "Search matches first name, last name, email, employee code, and position. " +
                      "Requires employees:list permission. Callable by TENANT_ADMIN, HR_MANAGER, or SITE_SUPERVISOR."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee list returned",
            content = @Content(schema = @Schema(implementation = EmployeeResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @PreAuthorize("hasAuthority('employees:list')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<EmployeeResponse>>> listEmployees(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Search term (name, email, code, position)") @RequestParam(required = false) String search,
            @Parameter(description = "Filter by status: active, inactive, terminated") @RequestParam(required = false) String status,
            @Parameter(description = "Filter by department") @RequestParam(required = false) String department,
            @Parameter(description = "Sort field") @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter(description = "Sort direction: asc or desc") @RequestParam(defaultValue = "desc") String sortDir,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (1–100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("List employees tenantId={} search={} status={} page={} size={} by={}",
                tenantId, search, status, page, size, userDetails.getUserId());
        PageResponse<EmployeeResponse> result = employeeService.listEmployees(
                tenantId, search, status, department, sortBy, sortDir, page, size,
                userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(
        summary = "Export employees to Excel",
        description = "Exports all employees matching the given filters as a downloadable .xlsx file. " +
                      "Supports the same search, status, and department filters as the list endpoint. " +
                      "Requires employees:list permission. Callable by TENANT_ADMIN, HR_MANAGER, or SITE_SUPERVISOR."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Excel file returned",
            content = @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    @PreAuthorize("hasAuthority('employees:list')")
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportEmployees(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Search term") @RequestParam(required = false) String search,
            @Parameter(description = "Filter by status: active, inactive, terminated") @RequestParam(required = false) String status,
            @Parameter(description = "Filter by department") @RequestParam(required = false) String department,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Export employees tenantId={} by={}", tenantId, userDetails.getUserId());
        byte[] data = employeeExportService.exportEmployees(
                tenantId, search, status, department, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"employees.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    @Operation(
        summary = "Update employee",
        description = "Partially updates an employee's HR profile. Only fields present in the request body are changed — " +
                      "send null to clear an optional field, omit a field to leave it unchanged. " +
                      "Status changes (active/inactive/terminated) are handled separately by the change-status endpoint. " +
                      "Requires employees:update permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee updated",
            content = @Content(schema = @Schema(implementation = EmployeeResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant or employee not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Employee code already exists in this tenant")
    })
    @PreAuthorize("hasAuthority('employees:update')")
    @PatchMapping("/{employeeId}")
    public ResponseEntity<ApiResponse<EmployeeResponse>> updateEmployee(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @Valid @RequestBody UpdateEmployeeRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Update employee id={} tenantId={} by={}", employeeId, tenantId, userDetails.getUserId());
        EmployeeResponse response = employeeService.updateEmployee(
                tenantId, employeeId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Change employee status",
        description = "Changes an employee's status to active, inactive, or terminated. " +
                      "Inactive employees cannot clock attendance. Terminated employees are considered no longer employed. " +
                      "Requires employees:update permission. Callable by TENANT_ADMIN or HR_MANAGER."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status updated",
            content = @Content(schema = @Schema(implementation = EmployeeResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid status value"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant or employee not found")
    })
    @PreAuthorize("hasAuthority('employees:update')")
    @PatchMapping("/{employeeId}/status")
    public ResponseEntity<ApiResponse<EmployeeResponse>> changeEmployeeStatus(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @Valid @RequestBody ChangeEmployeeStatusRequest request,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Change employee status id={} status={} tenantId={} by={}", employeeId, request.getStatus(), tenantId, userDetails.getUserId());
        EmployeeResponse response = employeeService.changeEmployeeStatus(
                tenantId, employeeId, request, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
        summary = "Get employee detail",
        description = "Returns the full employee profile including roles, workspace memberships, " +
                      "site/shift assignments, and Face ID status. Workspace, assignment, and Face ID " +
                      "fields are empty until those modules are implemented. " +
                      "Requires employees:read permission. Callable by TENANT_ADMIN, HR_MANAGER, or SITE_SUPERVISOR."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee detail returned",
            content = @Content(schema = @Schema(implementation = EmployeeDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient permissions"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Tenant or employee not found")
    })
    @PreAuthorize("hasAuthority('employees:read')")
    @GetMapping("/{employeeId}")
    public ResponseEntity<ApiResponse<EmployeeDetailResponse>> getEmployee(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @Parameter(description = "Employee UUID") @PathVariable UUID employeeId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Get employee id={} tenantId={} by={}", employeeId, tenantId, userDetails.getUserId());
        EmployeeDetailResponse result = employeeService.getEmployee(
                tenantId, employeeId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
