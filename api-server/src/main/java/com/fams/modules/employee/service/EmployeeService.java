package com.fams.modules.employee.service;

import com.fams.modules.employee.dto.request.ChangeEmployeeStatusRequest;
import com.fams.modules.employee.dto.request.CreateEmployeeRequest;
import com.fams.modules.employee.dto.request.UpdateEmployeeRequest;
import com.fams.modules.employee.dto.response.EmployeeDetailResponse;
import com.fams.modules.employee.dto.response.EmployeeImportError;
import com.fams.modules.employee.dto.response.EmployeeImportResponse;
import com.fams.modules.employee.dto.response.EmployeeResponse;
import com.fams.modules.employee.dto.response.FaceIdStatusDto;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.entity.FaceProfile;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.employee.specification.EmployeeSpecification;
import com.fams.modules.randomcheck.service.ScheduledCheckCancelService;
import com.fams.modules.rbac.dto.response.UserRoleResponse;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.subscription.service.PlanLimitEnforcementService;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.tenant.service.TenantSettingsService;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EmployeeService {

    private static final Set<String> SORTABLE_FIELDS = Set.of(
            "firstName", "lastName", "employeeCode", "status", "department", "hiredDate", "createdAt", "updatedAt");

    private final EmployeeRepository employeeRepository;
    private final UserRoleRepository userRoleRepository;
    private final TenantRepository tenantRepository;
    private final PlanLimitEnforcementService planLimitEnforcementService;
    private final FaceProfileRepository faceProfileRepository;
    private final TenantSettingsService tenantSettingsService;
    private final AssignmentRepository assignmentRepository;
    private final SiteScopeService siteScopeService;
    private final com.fams.modules.workspace.repository.WorkspaceMemberRepository workspaceMemberRepository;
    private final com.fams.modules.workspace.repository.WorkspaceRepository workspaceRepository;
    private final com.fams.modules.assignment.service.AssignmentService assignmentService;
    private final FaceIdService faceIdService;
    private final ScheduledCheckCancelService scheduledCheckCancelService;
    private final com.fams.modules.audit.service.AuditLogService auditLogService;

    public EmployeeService(EmployeeRepository employeeRepository,
                           UserRoleRepository userRoleRepository,
                           TenantRepository tenantRepository,
                           PlanLimitEnforcementService planLimitEnforcementService,
                           FaceProfileRepository faceProfileRepository,
                           TenantSettingsService tenantSettingsService,
                           AssignmentRepository assignmentRepository,
                           SiteScopeService siteScopeService,
                           com.fams.modules.workspace.repository.WorkspaceMemberRepository workspaceMemberRepository,
                           com.fams.modules.workspace.repository.WorkspaceRepository workspaceRepository,
                           com.fams.modules.assignment.service.AssignmentService assignmentService,
                           FaceIdService faceIdService,
                           ScheduledCheckCancelService scheduledCheckCancelService,
                           com.fams.modules.audit.service.AuditLogService auditLogService) {
        this.employeeRepository = employeeRepository;
        this.userRoleRepository = userRoleRepository;
        this.tenantRepository = tenantRepository;
        this.planLimitEnforcementService = planLimitEnforcementService;
        this.faceProfileRepository = faceProfileRepository;
        this.tenantSettingsService = tenantSettingsService;
        this.assignmentRepository = assignmentRepository;
        this.siteScopeService = siteScopeService;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceRepository = workspaceRepository;
        this.assignmentService = assignmentService;
        this.faceIdService = faceIdService;
        this.scheduledCheckCancelService = scheduledCheckCancelService;
        this.auditLogService = auditLogService;
    }

    /** Subset of Employee fields worth an audit trail — not every column (e.g. avatarUrl churns
     *  too often, timestamps are redundant with the audit row's own createdAt). email/phone are
     *  included deliberately: {@code AuditLogService.record} masks any key named "email"/"phone"
     *  in this map automatically (see MaskingUtils.maskAuditMap), so the PII stays protected in
     *  the audit trail the same as everywhere else, per the "Data Masking" story's own scope
     *  ("ẩn dữ liệu nhạy cảm khi trả về HOẶC ghi log"). */
    private Map<String, Object> employeeAuditSnapshot(Employee e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("employeeCode", e.getEmployeeCode());
        m.put("firstName", e.getFirstName());
        m.put("lastName", e.getLastName());
        m.put("email", e.getEmail());
        m.put("phone", e.getPhone());
        m.put("position", e.getPosition());
        m.put("department", e.getDepartment());
        m.put("status", e.getStatus());
        m.put("hiredDate", e.getHiredDate() != null ? e.getHiredDate().toString() : null);
        return m;
    }

    @Transactional
    public EmployeeResponse createEmployee(UUID tenantId, CreateEmployeeRequest request,
                                           UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("employees:create")) {
                throw new AccessDeniedException("You do not have permission to create employees in this tenant");
            }
        }

        planLimitEnforcementService.assertEmployeeLimit(tenantId);

        // Auto-generate employee code if not provided and prefix is configured
        String resolvedCode = request.getEmployeeCode();
        if (!org.springframework.util.StringUtils.hasText(resolvedCode)) {
            resolvedCode = tenantSettingsService.generateNextEmployeeCode(tenantId);
        }
        // Duplicate check (resolvedCode may still be null if no prefix)
        if (org.springframework.util.StringUtils.hasText(resolvedCode)
                && employeeRepository.existsByTenantIdAndEmployeeCodeAndDeletedAtIsNull(tenantId, resolvedCode)) {
            throw new DuplicateResourceException("Employee code '" + resolvedCode + "' already exists in this tenant");
        }

        // Validate departmentId if provided — now a Workspace id (org-chart consolidation,
        // see docs/api/workspace-management-api.md section 4)
        UUID deptId = request.getDepartmentId();
        String deptName = request.getDepartment();
        if (deptId != null) {
            com.fams.modules.workspace.entity.Workspace dept = workspaceRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(deptId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + deptId));
            deptName = dept.getName();  // sync text field
        }

        Employee employee = Employee.builder()
                .tenantId(tenantId)
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .email(org.springframework.util.StringUtils.hasText(request.getEmail())
                        ? request.getEmail().trim().toLowerCase() : null)
                .phone(request.getPhone())
                .employeeCode(resolvedCode)
                .position(request.getPosition())
                .department(deptName)
                .departmentId(deptId)
                .hiredDate(request.getHiredDate())
                .avatarUrl(request.getAvatarUrl())
                .status("active")
                .build();

        employeeRepository.save(employee);
        log.info("Employee created manually: id={} tenantId={} by={}", employee.getId(), tenantId, callerUserId);

        // Found via audit (2026-08-06): Employee create/update were never audited at all despite
        // being HR-critical data — every other mutation-heavy module (attendance, random-check)
        // already writes to AuditLogService. oldValue=null for create (nothing existed before).
        try {
            auditLogService.record(
                    tenantId, callerUserId, null,
                    "Employee", employee.getId().toString(), "employee_created",
                    null, employeeAuditSnapshot(employee),
                    com.fams.shared.security.HttpRequestUtils.currentRequestId(),
                    com.fams.shared.security.HttpRequestUtils.currentIpAddress(),
                    com.fams.shared.security.HttpRequestUtils.currentUserAgent());
        } catch (Exception e) {
            log.warn("Failed to record audit log for employee create id={}: {}", employee.getId(), e.getMessage());
        }

        return toResponse(employee);
    }

    @Transactional(readOnly = true)
    public PageResponse<EmployeeResponse> listEmployees(UUID tenantId, String search, String status,
                                                        String department, String sortBy, String sortDir,
                                                        int page, int size,
                                                        UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("employees:list")) {
                throw new AccessDeniedException("You do not have permission to list employees in this tenant");
            }
        }

        String resolvedSortBy = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, resolvedSortBy));

        // Employee has no direct siteId — a site-scoped caller's visibility is resolved via
        // Assignment (employee <-> site link) rather than a simple column predicate.
        java.util.Optional<Set<UUID>> allowedSiteIds =
                siteScopeService.resolveAllowedSiteIds(callerUserId, tenantId, callerIsPlatformAdmin);
        Set<UUID> restrictToEmployeeIds = null;
        if (allowedSiteIds.isPresent()) {
            if (allowedSiteIds.get().isEmpty()) {
                return PageResponse.from(Page.empty(pageable));
            }
            restrictToEmployeeIds = assignmentRepository
                    .findDistinctEmployeeIdsByTenantIdAndSiteIdIn(tenantId, allowedSiteIds.get());
            if (restrictToEmployeeIds.isEmpty()) {
                return PageResponse.from(Page.empty(pageable));
            }
        }

        Specification<Employee> spec = EmployeeSpecification.build(tenantId, search, status, department, restrictToEmployeeIds);
        Page<Employee> employeePage = employeeRepository.findAll(spec, pageable);

        List<UUID> employeeIds = employeePage.getContent().stream().map(Employee::getId).toList();
        Map<UUID, FaceIdStatusDto> faceIdMap = faceProfileRepository
                .findAllByEmployeeIdInAndTenantId(employeeIds, tenantId)
                .stream()
                .collect(Collectors.toMap(
                        FaceProfile::getEmployeeId,
                        FaceIdService::toDto));

        Page<EmployeeResponse> resultPage = employeePage.map(e ->
                toResponse(e, faceIdMap.getOrDefault(e.getId(), FaceIdStatusDto.builder()
                        .status("not_enrolled")
                        .consentGiven(false)
                        .consentGivenAt(null)
                        .enrolledAt(null)
                        .revokedAt(null)
                        .build())));

        return PageResponse.from(resultPage);
    }

    @Transactional
    public EmployeeResponse updateEmployee(UUID tenantId, UUID employeeId, UpdateEmployeeRequest request,
                                           UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("employees:update")) {
                throw new AccessDeniedException("You do not have permission to update employees in this tenant");
            }
        }

        Employee employee = employeeRepository.findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        Map<String, Object> before = employeeAuditSnapshot(employee);

        if (org.springframework.util.StringUtils.hasText(request.getEmployeeCode())
                && !request.getEmployeeCode().equals(employee.getEmployeeCode())
                && employeeRepository.existsByTenantIdAndEmployeeCodeAndDeletedAtIsNullAndIdNot(
                        tenantId, request.getEmployeeCode(), employeeId)) {
            throw new DuplicateResourceException(
                    "Employee code '" + request.getEmployeeCode() + "' already exists in this tenant");
        }

        if (org.springframework.util.StringUtils.hasText(request.getFirstName()))
            employee.setFirstName(request.getFirstName().trim());
        if (org.springframework.util.StringUtils.hasText(request.getLastName()))
            employee.setLastName(request.getLastName().trim());
        if (request.getEmail() != null)
            employee.setEmail(request.getEmail().isBlank() ? null : request.getEmail().trim().toLowerCase());
        if (request.getPhone() != null)
            employee.setPhone(request.getPhone().isBlank() ? null : request.getPhone());
        if (request.getEmployeeCode() != null)
            employee.setEmployeeCode(request.getEmployeeCode().isBlank() ? null : request.getEmployeeCode());
        if (request.getPosition() != null)
            employee.setPosition(request.getPosition().isBlank() ? null : request.getPosition());
        if (request.getDepartment() != null)
            employee.setDepartment(request.getDepartment().isBlank() ? null : request.getDepartment());
        if (request.getHiredDate() != null)
            employee.setHiredDate(request.getHiredDate());
        if (request.getAvatarUrl() != null)
            employee.setAvatarUrl(request.getAvatarUrl().isBlank() ? null : request.getAvatarUrl());
        if (request.getDepartmentId() != null) {
            com.fams.modules.workspace.entity.Workspace dept = workspaceRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(request.getDepartmentId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + request.getDepartmentId()));
            employee.setDepartmentId(dept.getId());
            employee.setDepartment(dept.getName());
        }

        employeeRepository.save(employee);
        log.info("Employee updated: id={} tenantId={} by={}", employeeId, tenantId, callerUserId);

        try {
            auditLogService.record(
                    tenantId, callerUserId, null,
                    "Employee", employee.getId().toString(), "employee_updated",
                    before, employeeAuditSnapshot(employee),
                    com.fams.shared.security.HttpRequestUtils.currentRequestId(),
                    com.fams.shared.security.HttpRequestUtils.currentIpAddress(),
                    com.fams.shared.security.HttpRequestUtils.currentUserAgent());
        } catch (Exception e) {
            log.warn("Failed to record audit log for employee update id={}: {}", employeeId, e.getMessage());
        }

        return toResponse(employee);
    }

    @Transactional
    public EmployeeResponse changeEmployeeStatus(UUID tenantId, UUID employeeId,
                                                 ChangeEmployeeStatusRequest request,
                                                 UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("employees:update")) {
                throw new AccessDeniedException("You do not have permission to update employees in this tenant");
            }
        }

        Employee employee = employeeRepository.findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        employee.setStatus(request.getStatus());
        employeeRepository.save(employee);
        log.info("Employee status changed: id={} status={} tenantId={} by={}", employeeId, request.getStatus(), tenantId, callerUserId);

        // Biometric data retention: the purpose for holding a terminated employee's face
        // enrollment has ended, so revoke it immediately rather than leaving it to the weekly
        // retention job — matches the same treatment as any other employee-initiated revoke
        // (purges the embedding + stored photos via fams-ai). Deliberately NOT done for
        // "inactive" (temporary/reversible, e.g. leave of absence) — only "terminated" is final.
        if ("terminated".equals(request.getStatus())) {
            try {
                faceIdService.autoRevokeOnTermination(tenantId, employeeId);
            } catch (Exception e) {
                log.warn("Failed to auto-revoke Face ID on termination: employeeId={} error={}",
                        employeeId, e.getMessage());
            }

            // Found via audit (2026-08-01): terminating an employee stopped NEW random checks
            // from being generated for them (the generator's employees.status='active' join), but
            // any check ALREADY generated earlier that same day (before termination) was left
            // untouched — it would silently sit until NoResponseViolationJob timed it out as
            // no_response and raised a violation for someone no longer employed. Mirrors exactly
            // what AssignmentService.cancelAssignment already does for an HR-initiated cancel —
            // termination is just another way an assignment becomes invalid.
            try {
                List<Assignment> activeAssignments = assignmentRepository
                        .findByTenantIdAndEmployeeIdAndDeletedAtIsNullOrderByStartDateDesc(tenantId, employeeId)
                        .stream()
                        .filter(a -> "active".equals(a.getStatus()))
                        .collect(Collectors.toList());
                for (Assignment a : activeAssignments) {
                    int cancelled = scheduledCheckCancelService.cancelPendingByAssignment(a.getId());
                    if (cancelled > 0) {
                        log.info("Auto-cancelled {} scheduled check(s) due to employee termination: "
                                + "employeeId={} assignmentId={}", cancelled, employeeId, a.getId());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to auto-cancel pending random checks on termination: employeeId={} error={}",
                        employeeId, e.getMessage());
            }
        }

        return toResponse(employee);
    }

    @Transactional(readOnly = true)
    public EmployeeDetailResponse getEmployee(UUID tenantId, UUID employeeId,
                                              UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("employees:read")) {
                throw new AccessDeniedException("You do not have permission to view employees in this tenant");
            }
        }

        assertEmployeeInScope(callerUserId, tenantId, employeeId, callerIsPlatformAdmin);

        Employee employee = employeeRepository.findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        List<UserRoleResponse> roles = Collections.emptyList();
        if (employee.getUserId() != null) {
            List<UserRole> userRoles = userRoleRepository
                    .findActiveByUserIdAndTenantId(employee.getUserId(), tenantId);
            roles = userRoles.stream().map(ur -> UserRoleResponse.builder()
                    .id(ur.getId())
                    .userId(ur.getUserId())
                    .roleId(ur.getRole().getId())
                    .roleName(ur.getRole().getName())
                    .tenantId(ur.getTenantId())
                    .assignedBy(ur.getAssignedBy())
                    .createdAt(ur.getCreatedAt())
                    .updatedAt(ur.getUpdatedAt())
                    .build()).toList();
        }

        return EmployeeDetailResponse.builder()
                .id(employee.getId())
                .tenantId(employee.getTenantId())
                .userId(employee.getUserId())
                .employeeCode(employee.getEmployeeCode())
                .firstName(employee.getFirstName())
                .lastName(employee.getLastName())
                .email(employee.getEmail())
                .phone(employee.getPhone())
                .position(employee.getPosition())
                .department(employee.getDepartment())
                .departmentId(employee.getDepartmentId())
                .status(employee.getStatus())
                .hiredDate(employee.getHiredDate())
                .avatarUrl(employee.getAvatarUrl())
                .roles(roles)
                .workspaces(resolveWorkspaceMemberships(employee.getId(), tenantId))
                .assignments(assignmentRepository
                        .findByTenantIdAndEmployeeIdAndDeletedAtIsNullOrderByStartDateDesc(tenantId, employee.getId())
                        .stream()
                        .map(assignmentService::toResponse)
                        .toList())
                .faceId(faceProfileRepository
                        .findByEmployeeIdAndTenantId(employee.getId(), tenantId)
                        .map(FaceIdService::toDto)
                        .orElse(FaceIdStatusDto.builder()
                                .status("not_enrolled")
                                .consentGiven(false)
                                .consentGivenAt(null)
                                .enrolledAt(null)
                                .revokedAt(null)
                                .build()))
                .createdAt(employee.getCreatedAt())
                .updatedAt(employee.getUpdatedAt())
                .piiMasked(!com.fams.shared.util.PiiAccess.currentCallerCanViewUnmaskedPii())
                .build();
    }

    private List<com.fams.modules.employee.dto.response.EmployeeWorkspaceMembershipResponse> resolveWorkspaceMemberships(
            UUID employeeId, UUID tenantId) {
        List<com.fams.modules.workspace.entity.WorkspaceMember> memberships =
                workspaceMemberRepository.findByEmployeeIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId);
        if (memberships.isEmpty()) {
            return Collections.emptyList();
        }
        Map<UUID, String> workspaceNamesById = workspaceRepository
                .findByTenantIdAndDeletedAtIsNullOrderByNameAsc(tenantId).stream()
                .collect(Collectors.toMap(com.fams.modules.workspace.entity.Workspace::getId,
                        com.fams.modules.workspace.entity.Workspace::getName));
        return memberships.stream()
                .map(m -> com.fams.modules.employee.dto.response.EmployeeWorkspaceMembershipResponse.builder()
                        .id(m.getId())
                        .workspaceId(m.getWorkspaceId())
                        .workspaceName(workspaceNamesById.get(m.getWorkspaceId()))
                        .role(m.getRole())
                        .assignedAt(m.getCreatedAt())
                        .build())
                .toList();
    }

    /** Site-scoped callers may only view an employee who has (or had) an assignment at one
     *  of their allowed sites — see SiteScopeService and the Assignment entity. */
    private void assertEmployeeInScope(UUID callerUserId, UUID tenantId, UUID employeeId, boolean callerIsPlatformAdmin) {
        java.util.Optional<Set<UUID>> allowedSiteIds =
                siteScopeService.resolveAllowedSiteIds(callerUserId, tenantId, callerIsPlatformAdmin);
        if (allowedSiteIds.isEmpty()) {
            return;
        }
        Set<UUID> sites = allowedSiteIds.get();
        if (sites.isEmpty() || !assignmentRepository.existsByTenantIdAndEmployeeIdAndSiteIdInAndDeletedAtIsNull(
                tenantId, employeeId, sites)) {
            throw new AccessDeniedException("You do not have permission to view this employee");
        }
    }

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern CODE_PATTERN  = Pattern.compile("^[A-Za-z0-9\\-_]+$");

    @Transactional
    public EmployeeImportResponse importEmployees(UUID tenantId, MultipartFile file,
                                                  UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("employees:create")) {
                throw new AccessDeniedException("You do not have permission to create employees in this tenant");
            }
        }

        List<EmployeeImportError> errors = new ArrayList<>();
        int successCount = 0;
        int totalRows = 0;
        Set<String> codesSeenInBatch = new HashSet<>();

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null) {
                return EmployeeImportResponse.builder()
                        .totalRows(0).successCount(0).failedCount(0).errors(List.of()).build();
            }

            Map<String, Integer> colIndex = new HashMap<>();
            for (Cell cell : header) {
                String name = cell.getStringCellValue().trim().toLowerCase();
                colIndex.put(name, cell.getColumnIndex());
            }

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowBlank(row)) continue;
                totalRows++;
                int displayRow = i + 1;

                String firstName  = str(row, colIndex.get("firstname"));
                String lastName   = str(row, colIndex.get("lastname"));
                String email      = str(row, colIndex.get("email"));
                String phone      = str(row, colIndex.get("phone"));
                String code       = str(row, colIndex.get("employeecode"));
                String position   = str(row, colIndex.get("position"));
                String department = str(row, colIndex.get("department"));
                String hiredDateStr = str(row, colIndex.get("hireddate"));

                List<EmployeeImportError> rowErrors = new ArrayList<>();

                if (firstName == null || firstName.isBlank())
                    rowErrors.add(err(displayRow, "firstName", "First name is required"));
                else if (firstName.length() > 100)
                    rowErrors.add(err(displayRow, "firstName", "First name must be 100 characters or fewer"));

                if (lastName == null || lastName.isBlank())
                    rowErrors.add(err(displayRow, "lastName", "Last name is required"));
                else if (lastName.length() > 100)
                    rowErrors.add(err(displayRow, "lastName", "Last name must be 100 characters or fewer"));

                if (email != null && !email.isBlank() && !EMAIL_PATTERN.matcher(email).matches())
                    rowErrors.add(err(displayRow, "email", "Must be a valid email address"));

                if (phone != null && phone.length() > 30)
                    rowErrors.add(err(displayRow, "phone", "Phone must be 30 characters or fewer"));

                if (code != null && !code.isBlank()) {
                    if (code.length() > 50)
                        rowErrors.add(err(displayRow, "employeeCode", "Employee code must be 50 characters or fewer"));
                    else if (!CODE_PATTERN.matcher(code).matches())
                        rowErrors.add(err(displayRow, "employeeCode", "Employee code may only contain letters, digits, hyphens, and underscores"));
                    else if (codesSeenInBatch.contains(code))
                        rowErrors.add(err(displayRow, "employeeCode", "Duplicate employee code in this import file"));
                    else if (employeeRepository.existsByTenantIdAndEmployeeCodeAndDeletedAtIsNull(tenantId, code))
                        rowErrors.add(err(displayRow, "employeeCode", "Employee code '" + code + "' already exists in this tenant"));
                }

                LocalDate hiredDate = null;
                if (hiredDateStr != null && !hiredDateStr.isBlank()) {
                    try {
                        hiredDate = LocalDate.parse(hiredDateStr);
                    } catch (DateTimeParseException e) {
                        rowErrors.add(err(displayRow, "hiredDate", "Date must be in YYYY-MM-DD format"));
                    }
                }

                if (!rowErrors.isEmpty()) {
                    errors.addAll(rowErrors);
                    continue;
                }

                if (code != null && !code.isBlank()) codesSeenInBatch.add(code);

                Employee employee = Employee.builder()
                        .tenantId(tenantId)
                        .firstName(firstName.trim())
                        .lastName(lastName.trim())
                        .email(email != null && !email.isBlank() ? email.trim().toLowerCase() : null)
                        .phone(phone != null && !phone.isBlank() ? phone.trim() : null)
                        .employeeCode(code != null && !code.isBlank() ? code : null)
                        .position(position != null && !position.isBlank() ? position : null)
                        .department(department != null && !department.isBlank() ? department : null)
                        .hiredDate(hiredDate)
                        .status("active")
                        .build();
                employeeRepository.save(employee);
                successCount++;
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read Excel file: " + e.getMessage());
        }

        int failedCount = totalRows - successCount;
        log.info("Employee import: tenantId={} total={} success={} failed={} by={}",
                tenantId, totalRows, successCount, failedCount, callerUserId);
        return EmployeeImportResponse.builder()
                .totalRows(totalRows)
                .successCount(successCount)
                .failedCount(failedCount)
                .errors(errors)
                .build();
    }

    private static final DataFormatter DATA_FORMATTER = new DataFormatter();

    private String str(Row row, Integer colIdx) {
        if (colIdx == null) return null;
        Cell cell = row.getCell(colIdx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        String val = DATA_FORMATTER.formatCellValue(cell).trim();
        return val.isEmpty() ? null : val;
    }

    private boolean isRowBlank(Row row) {
        for (Cell cell : row) {
            if (cell != null) {
                String v = DATA_FORMATTER.formatCellValue(cell).trim();
                if (!v.isEmpty()) return false;
            }
        }
        return true;
    }

    private EmployeeImportError err(int row, String field, String message) {
        return EmployeeImportError.builder().row(row).field(field).message(message).build();
    }

    public EmployeeResponse toResponse(Employee e) {
        return toResponse(e, null);
    }

    public EmployeeResponse toResponse(Employee e, FaceIdStatusDto faceId) {
        return EmployeeResponse.builder()
                .id(e.getId())
                .tenantId(e.getTenantId())
                .userId(e.getUserId())
                .employeeCode(e.getEmployeeCode())
                .firstName(e.getFirstName())
                .lastName(e.getLastName())
                .email(e.getEmail())
                .phone(e.getPhone())
                .position(e.getPosition())
                .department(e.getDepartment())
                .departmentId(e.getDepartmentId())
                .status(e.getStatus())
                .hiredDate(e.getHiredDate())
                .avatarUrl(e.getAvatarUrl())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .faceId(faceId)
                .piiMasked(!com.fams.shared.util.PiiAccess.currentCallerCanViewUnmaskedPii())
                .build();
    }
}
