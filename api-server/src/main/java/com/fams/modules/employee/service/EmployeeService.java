package com.fams.modules.employee.service;

import com.fams.modules.employee.dto.request.ChangeEmployeeStatusRequest;
import com.fams.modules.employee.dto.request.CreateEmployeeRequest;
import com.fams.modules.employee.dto.request.UpdateEmployeeRequest;
import com.fams.modules.employee.dto.response.EmployeeDetailResponse;
import com.fams.modules.employee.dto.response.EmployeeImportError;
import com.fams.modules.employee.dto.response.EmployeeImportResponse;
import com.fams.modules.employee.dto.response.EmployeeImportValidationResponse;
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
import com.fams.modules.rbac.entity.Role;
import com.fams.modules.rbac.repository.RoleRepository;
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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
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
    private final RoleRepository roleRepository;
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
    private final com.fams.modules.site.repository.SiteRepository siteRepository;

    public EmployeeService(EmployeeRepository employeeRepository,
                           UserRoleRepository userRoleRepository,
                           RoleRepository roleRepository,
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
                           com.fams.modules.audit.service.AuditLogService auditLogService,
                           com.fams.modules.site.repository.SiteRepository siteRepository) {
        this.employeeRepository = employeeRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
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
        this.siteRepository = siteRepository;
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
        m.put("nationalId", e.getNationalId());
        m.put("position", e.getPosition());
        m.put("department", e.getDepartment());
        m.put("status", e.getStatus());
        m.put("terminatedAt", e.getTerminatedAt() != null ? e.getTerminatedAt().toString() : null);
        m.put("hiredDate", e.getHiredDate() != null ? e.getHiredDate().toString() : null);
        return m;
    }

    /** Mirrors the role-ownership rule used everywhere else a role gets attached to a tenant
     *  context (e.g. UserRoleService#assignRole, EmployeeInvitationService#sendInvitation): a
     *  role must either be a shared tenant-tier system role, or belong to this exact tenant —
     *  never another tenant's custom role, never a platform-tier role. */
    private void validatePlannedRoleId(UUID tenantId, UUID roleId) {
        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));
        if (role.getTenantId() != null && !role.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Role " + roleId + " does not belong to tenant " + tenantId);
        }
        if (role.getTenantId() == null && (!role.isSystem() || role.isPlatformRole())) {
            throw new IllegalArgumentException(
                    "Role '" + role.getName() + "' is a platform-scoped role and cannot be used as a planned role");
        }
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

        planLimitEnforcementService.assertEmployeeLimit(tenantId, callerUserId);

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

        if (request.getPlannedRoleId() != null) {
            validatePlannedRoleId(tenantId, request.getPlannedRoleId());
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
                .nationalId(request.getNationalId())
                .hiredDate(request.getHiredDate())
                .avatarUrl(request.getAvatarUrl())
                .plannedRoleId(request.getPlannedRoleId())
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
                                                        String department, Boolean faceRegistered, UUID workspaceId,
                                                        String sortBy, String sortDir,
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

        Specification<Employee> spec = EmployeeSpecification.build(
                tenantId, search, status, department, restrictToEmployeeIds, faceRegistered, workspaceId);
        Page<Employee> employeePage = employeeRepository.findAll(spec, pageable);

        List<UUID> employeeIds = employeePage.getContent().stream().map(Employee::getId).toList();
        Map<UUID, FaceIdStatusDto> faceIdMap = faceProfileRepository
                .findAllByEmployeeIdInAndTenantId(employeeIds, tenantId)
                .stream()
                .collect(Collectors.toMap(
                        FaceProfile::getEmployeeId,
                        FaceIdService::toDto));

        // Batch-fetch each employee's system role name(s) in this tenant — shown as a column
        // in the employee list so "who is TENANT_ADMIN/HR_MANAGER/..." is visible at a glance
        // instead of opening each employee's profile one at a time (2026-08-14 user feedback).
        List<UUID> userIds = employeePage.getContent().stream()
                .map(Employee::getUserId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<UUID, List<String>> roleNamesByUserId = userIds.isEmpty() ? Map.of()
                : userRoleRepository.findAllWithRoleByUserIdInAndTenantId(userIds, tenantId).stream()
                        .collect(Collectors.groupingBy(
                                com.fams.modules.rbac.entity.UserRole::getUserId,
                                Collectors.mapping(ur -> ur.getRole().getName(), Collectors.toList())));

        Page<EmployeeResponse> resultPage = employeePage.map(e ->
                toResponse(e, faceIdMap.getOrDefault(e.getId(), FaceIdStatusDto.builder()
                        .status("not_enrolled")
                        .consentGiven(false)
                        .consentGivenAt(null)
                        .enrolledAt(null)
                        .revokedAt(null)
                        .build()),
                        e.getUserId() != null ? roleNamesByUserId.getOrDefault(e.getUserId(), List.of()) : List.of()));

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
        if (request.getNationalId() != null)
            employee.setNationalId(request.getNationalId().isBlank() ? null : request.getNationalId());
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
        if (request.getPlannedRoleId() != null) {
            validatePlannedRoleId(tenantId, request.getPlannedRoleId());
            employee.setPlannedRoleId(request.getPlannedRoleId());
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

        Map<String, Object> before = employeeAuditSnapshot(employee);
        String previousStatus = employee.getStatus();
        employee.setStatus(request.getStatus());

        // #40 gap fix (2026-08-16): AC requires recording WHEN termination happened, not just
        // the current status — a bare status column can't answer "since when has this person
        // been gone" (payroll cutoffs, historical reports). Cleared if HR reverses the decision
        // (status moves away from terminated), since the person is no longer terminated.
        if ("terminated".equals(request.getStatus())) {
            employee.setTerminatedAt(OffsetDateTime.now());
        } else if (!"terminated".equals(previousStatus)) {
            // no-op: wasn't terminated before, isn't now — nothing to clear
        } else {
            employee.setTerminatedAt(null);
        }

        employeeRepository.save(employee);
        log.info("Employee status changed: id={} status={} tenantId={} by={}", employeeId, request.getStatus(), tenantId, callerUserId);

        try {
            auditLogService.record(
                    tenantId, callerUserId, null,
                    "Employee", employee.getId().toString(), "employee_status_changed",
                    before, employeeAuditSnapshot(employee),
                    com.fams.shared.security.HttpRequestUtils.currentRequestId(),
                    com.fams.shared.security.HttpRequestUtils.currentIpAddress(),
                    com.fams.shared.security.HttpRequestUtils.currentUserAgent());
        } catch (Exception e) {
            log.warn("Failed to record audit log for employee status change id={}: {}", employee.getId(), e.getMessage());
        }

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
            //
            // #40 gap fix (2026-08-16): this used to ONLY cancel pending scheduled checks and
            // never actually touched the Assignment row itself (unlike AssignmentService
            // .cancelAssignment, which does `status=cancelled`) — confirmed live: a terminated
            // employee's Assignment stayed "active" indefinitely, so the site still looked
            // staffed by someone no longer employed. Now cancels the Assignment too, same as an
            // HR-initiated cancel would.
            try {
                List<Assignment> activeAssignments = assignmentRepository
                        .findByTenantIdAndEmployeeIdAndDeletedAtIsNullOrderByStartDateDesc(tenantId, employeeId)
                        .stream()
                        .filter(a -> "active".equals(a.getStatus()))
                        .collect(Collectors.toList());
                for (Assignment a : activeAssignments) {
                    a.setStatus("cancelled");
                    assignmentRepository.save(a);
                    int cancelled = scheduledCheckCancelService.cancelPendingByAssignment(
                            tenantId, a.getId(), callerUserId, "Employee terminated");
                    log.info("Auto-cancelled assignment (and {} pending scheduled check(s)) due to employee "
                            + "termination: employeeId={} assignmentId={}", cancelled, employeeId, a.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to auto-cancel assignments/pending random checks on termination: employeeId={} error={}",
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
            // Resolve site names once for every site referenced across this user's assignments —
            // previously siteIds/sites were left null here, so a site-scoped role always showed
            // as tenant-wide ("Toàn công ty") on the employee's Roles tab (#11).
            Set<UUID> allSiteIds = userRoles.stream()
                    .flatMap(ur -> ur.getSiteIds().stream())
                    .collect(java.util.stream.Collectors.toSet());
            Map<UUID, String> siteNamesById = allSiteIds.isEmpty()
                    ? Collections.emptyMap()
                    : siteRepository.findAllById(allSiteIds).stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    com.fams.modules.site.entity.Site::getId,
                                    com.fams.modules.site.entity.Site::getName));
            roles = userRoles.stream().map(ur -> UserRoleResponse.builder()
                    .id(ur.getId())
                    .userId(ur.getUserId())
                    .roleId(ur.getRole().getId())
                    .roleName(ur.getRole().getName())
                    .tenantId(ur.getTenantId())
                    .siteIds(new ArrayList<>(ur.getSiteIds()))
                    .sites(ur.getSiteIds().stream()
                            .map(sid -> com.fams.modules.rbac.dto.response.SiteRefResponse.builder()
                                    .id(sid)
                                    .name(siteNamesById.getOrDefault(sid, "Công trình đã xoá"))
                                    .build())
                            .toList())
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
                .nationalId(employee.getNationalId())
                .status(employee.getStatus())
                .terminatedAt(employee.getTerminatedAt())
                .hiredDate(employee.getHiredDate())
                .avatarUrl(employee.getAvatarUrl())
                .roles(roles)
                .workspaces(resolveWorkspaceMemberships(employee.getId(), tenantId))
                .assignments(assignmentService.toResponsesWithContext(assignmentRepository
                        .findByTenantIdAndEmployeeIdAndDeletedAtIsNullOrderByStartDateDesc(
                                tenantId, employee.getId())))
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
                        .isPrimary(m.isPrimary())
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
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9\\-_]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[+0-9() .-]+$");
    private static final long MAX_IMPORT_FILE_SIZE = 5L * 1024 * 1024;
    private static final DateTimeFormatter VIETNAMESE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final String[] IMPORT_TEMPLATE_HEADERS = {
            "Mã nhân viên", "Họ và tên đệm", "Tên", "Email", "Số điện thoại",
            "Chức vụ", "Phòng ban", "Ngày vào làm"
    };
    private static final Set<String> REQUIRED_IMPORT_HEADERS = Set.of("firstname", "lastname");

    private record ImportRowData(
            String firstName,
            String lastName,
            String email,
            String phone,
            String employeeCode,
            String position,
            String department,
            String hiredDateText,
            LocalDate hiredDate) {
    }

    private record AnalyzedImportRow(
            int rowNumber,
            ImportRowData data,
            List<EmployeeImportError> errors) {
    }

    private record ImportAnalysis(
            int totalRows,
            List<AnalyzedImportRow> rows,
            List<EmployeeImportError> structureErrors) {

        private List<EmployeeImportError> allErrors() {
            List<EmployeeImportError> all = new ArrayList<>(structureErrors);
            rows.forEach(row -> all.addAll(row.errors()));
            return all;
        }

        private int validRows() {
            if (!structureErrors.isEmpty()) return 0;
            return (int) rows.stream().filter(row -> row.errors().isEmpty()).count();
        }

        private int invalidRows() {
            if (!structureErrors.isEmpty()) return totalRows;
            return totalRows - validRows();
        }
    }

    @Transactional
    public EmployeeImportResponse importEmployees(UUID tenantId, MultipartFile file,
                                                  UUID callerUserId, boolean callerIsPlatformAdmin) {
        assertImportAccess(tenantId, callerUserId, callerIsPlatformAdmin);
        ImportAnalysis analysis = analyzeImportFile(tenantId, file);
        int successCount = 0;

        if (analysis.structureErrors().isEmpty()) {
            // Validate the whole batch in one query and keep import atomic for a plan-level
            // failure. Row-level validation remains partial for backward API compatibility.
            planLimitEnforcementService.assertEmployeeCapacity(
                    tenantId, analysis.validRows(), callerUserId);
            for (AnalyzedImportRow analyzedRow : analysis.rows()) {
                if (!analyzedRow.errors().isEmpty()) continue;
                ImportRowData row = analyzedRow.data();
                Employee employee = Employee.builder()
                        .tenantId(tenantId)
                        .firstName(row.firstName().trim())
                        .lastName(row.lastName().trim())
                        .email(normalizedOrNull(row.email(), true))
                        .phone(normalizedOrNull(row.phone(), false))
                        .employeeCode(normalizedOrNull(row.employeeCode(), false))
                        .position(normalizedOrNull(row.position(), false))
                        .department(normalizedOrNull(row.department(), false))
                        .hiredDate(row.hiredDate())
                        .status("active")
                        .build();
                employeeRepository.save(employee);
                successCount++;
            }
        }

        int failedCount = analysis.totalRows() - successCount;
        log.info("Employee import: tenantId={} total={} success={} failed={} by={}",
                tenantId, analysis.totalRows(), successCount, failedCount, callerUserId);
        return EmployeeImportResponse.builder()
                .totalRows(analysis.totalRows())
                .successCount(successCount)
                .failedCount(failedCount)
                .errors(analysis.allErrors())
                .build();
    }

    @Transactional(readOnly = true)
    public EmployeeImportValidationResponse validateEmployeesImport(
            UUID tenantId, MultipartFile file, UUID callerUserId, boolean callerIsPlatformAdmin) {
        assertImportAccess(tenantId, callerUserId, callerIsPlatformAdmin);
        ImportAnalysis analysis = analyzeImportFile(tenantId, file);
        List<EmployeeImportError> errors = analysis.allErrors();
        if (errors.isEmpty()) {
            planLimitEnforcementService.assertEmployeeCapacity(
                    tenantId, analysis.validRows(), callerUserId);
        }
        return EmployeeImportValidationResponse.builder()
                .valid(errors.isEmpty() && analysis.totalRows() > 0)
                .totalRows(analysis.totalRows())
                .validRows(analysis.validRows())
                .invalidRows(analysis.invalidRows())
                .errors(errors)
                .build();
    }

    @Transactional(readOnly = true)
    public byte[] createEmployeeImportTemplate(
            UUID tenantId, UUID callerUserId, boolean callerIsPlatformAdmin) {
        assertImportAccess(tenantId, callerUserId, callerIsPlatformAdmin);
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet dataSheet = workbook.createSheet("Danh sách nhân viên");
            dataSheet.createFreezePane(0, 1);
            dataSheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0, 0, 0, IMPORT_TEMPLATE_HEADERS.length - 1));

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);

            Row header = dataSheet.createRow(0);
            for (int i = 0; i < IMPORT_TEMPLATE_HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(IMPORT_TEMPLATE_HEADERS[i]);
                cell.setCellStyle(headerStyle);
                dataSheet.setColumnWidth(i, switch (i) {
                    case 1, 3, 6 -> 26 * 256;
                    case 4, 5 -> 20 * 256;
                    default -> 18 * 256;
                });
            }

            Sheet guideSheet = workbook.createSheet("Hướng dẫn");
            String[][] guideRows = {
                    {"Cột", "Bắt buộc", "Định dạng / quy tắc", "Ví dụ"},
                    {"Mã nhân viên", "Không", "Tối đa 50 ký tự; chỉ chữ, số, dấu - và _; không trùng trong công ty", "NV-001"},
                    {"Họ và tên đệm", "Có", "Tối đa 100 ký tự", "Nguyễn Văn"},
                    {"Tên", "Có", "Tối đa 100 ký tự", "An"},
                    {"Email", "Không", "Đúng định dạng email; tối đa 255 ký tự", "an.nguyen@example.com"},
                    {"Số điện thoại", "Không", "Tối đa 30 ký tự; chỉ số và các ký tự + ( ) . -", "0901234567"},
                    {"Chức vụ", "Không", "Nội dung văn bản; tối đa 100 ký tự", "Kỹ sư hiện trường"},
                    {"Phòng ban", "Không", "Tên phòng ban hiển thị trên hồ sơ; tối đa 100 ký tự", "Kỹ thuật"},
                    {"Ngày vào làm", "Không", "dd/MM/yyyy hoặc yyyy-MM-dd", "01/09/2026"}
            };
            for (int r = 0; r < guideRows.length; r++) {
                Row row = guideSheet.createRow(r);
                for (int c = 0; c < guideRows[r].length; c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(guideRows[r][c]);
                    if (r == 0) cell.setCellStyle(headerStyle);
                }
            }
            guideSheet.createFreezePane(0, 1);
            guideSheet.setColumnWidth(0, 24 * 256);
            guideSheet.setColumnWidth(1, 14 * 256);
            guideSheet.setColumnWidth(2, 72 * 256);
            guideSheet.setColumnWidth(3, 30 * 256);
            workbook.setActiveSheet(0);
            return toBytes(workbook);
        } catch (IOException e) {
            throw new IllegalStateException("Không thể tạo file Excel mẫu", e);
        }
    }

    private void assertImportAccess(UUID tenantId, UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("employees:create")) {
                throw new AccessDeniedException("You do not have permission to create employees in this tenant");
            }
        }
    }

    private ImportAnalysis analyzeImportFile(UUID tenantId, MultipartFile file) {
        validateImportFile(file);
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            if (workbook.getNumberOfSheets() == 0) {
                return new ImportAnalysis(0, List.of(),
                        List.of(err(1, "file", "File Excel không có trang dữ liệu")));
            }
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null || isRowBlank(header)) {
                return new ImportAnalysis(countDataRows(sheet), List.of(),
                        List.of(err(1, "header", "Dòng đầu tiên phải là tiêu đề cột")));
            }

            Map<String, Integer> colIndex = new HashMap<>();
            List<EmployeeImportError> structureErrors = new ArrayList<>();
            for (Cell cell : header) {
                String raw = DATA_FORMATTER.formatCellValue(cell).trim();
                if (raw.isEmpty()) continue;
                String canonical = canonicalImportHeader(raw.toLowerCase(Locale.ROOT));
                Integer previous = colIndex.putIfAbsent(canonical, cell.getColumnIndex());
                if (previous != null) {
                    structureErrors.add(err(1, canonical,
                            "Cột ‘" + raw + "’ bị lặp trong dòng tiêu đề"));
                }
            }
            for (String required : REQUIRED_IMPORT_HEADERS) {
                if (!colIndex.containsKey(required)) {
                    structureErrors.add(err(1, required,
                            "Thiếu cột bắt buộc ‘" + importFieldLabel(required) + "’"));
                }
            }

            int totalRows = countDataRows(sheet);
            if (totalRows == 0) {
                structureErrors.add(err(2, "file", "File chưa có dòng dữ liệu nhân viên"));
            }
            if (!structureErrors.isEmpty()) {
                return new ImportAnalysis(totalRows, List.of(), structureErrors);
            }

            List<AnalyzedImportRow> rows = new ArrayList<>();
            Set<String> codesSeenInBatch = new HashSet<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowBlank(row)) continue;
                int displayRow = i + 1;
                String hiredDateText = dateStr(row, colIndex.get("hireddate"));
                LocalDate hiredDate = parseImportDate(hiredDateText);
                ImportRowData data = new ImportRowData(
                        str(row, colIndex.get("firstname")),
                        str(row, colIndex.get("lastname")),
                        str(row, colIndex.get("email")),
                        str(row, colIndex.get("phone")),
                        str(row, colIndex.get("employeecode")),
                        str(row, colIndex.get("position")),
                        str(row, colIndex.get("department")),
                        hiredDateText,
                        hiredDate);
                List<EmployeeImportError> rowErrors = validateImportRow(
                        tenantId, displayRow, data, codesSeenInBatch);
                if (rowErrors.isEmpty() && data.employeeCode() != null) {
                    codesSeenInBatch.add(data.employeeCode());
                }
                rows.add(new AnalyzedImportRow(displayRow, data, rowErrors));
            }
            return new ImportAnalysis(totalRows, rows, structureErrors);
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalArgumentException invalidFile
                    && invalidFile.getMessage() != null
                    && invalidFile.getMessage().startsWith("File ")) {
                throw invalidFile;
            }
            throw new IllegalArgumentException(
                    "Không thể đọc file Excel. Hãy tải lại file mẫu và lưu đúng định dạng .xlsx", e);
        }
    }

    private void validateImportFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File Excel không được để trống");
        }
        if (file.getSize() > MAX_IMPORT_FILE_SIZE) {
            throw new IllegalArgumentException("File Excel vượt quá dung lượng tối đa 5 MB");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("Chỉ hỗ trợ file Excel định dạng .xlsx");
        }
    }

    private int countDataRows(Sheet sheet) {
        int count = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && !isRowBlank(row)) count++;
        }
        return count;
    }

    private List<EmployeeImportError> validateImportRow(
            UUID tenantId, int displayRow, ImportRowData data, Set<String> codesSeenInBatch) {
        String firstName = data.firstName();
        String lastName = data.lastName();
        String email = data.email();
        String phone = data.phone();
        String code = data.employeeCode();
        List<EmployeeImportError> rowErrors = new ArrayList<>();

        if (firstName == null || firstName.isBlank())
            rowErrors.add(err(displayRow, "firstName", "Tên là trường bắt buộc"));
        else if (firstName.length() > 100)
            rowErrors.add(err(displayRow, "firstName", "Tên không được vượt quá 100 ký tự"));

        if (lastName == null || lastName.isBlank())
            rowErrors.add(err(displayRow, "lastName", "Họ và tên đệm là trường bắt buộc"));
        else if (lastName.length() > 100)
            rowErrors.add(err(displayRow, "lastName", "Họ và tên đệm không được vượt quá 100 ký tự"));

        if (email != null && !email.isBlank() && !EMAIL_PATTERN.matcher(email).matches())
            rowErrors.add(err(displayRow, "email", "Email không đúng định dạng"));
        else if (email != null && email.length() > 255)
            rowErrors.add(err(displayRow, "email", "Email không được vượt quá 255 ký tự"));

        if (phone != null && !phone.isBlank()) {
            if (phone.length() > 30)
                rowErrors.add(err(displayRow, "phone", "Số điện thoại không được vượt quá 30 ký tự"));
            else if (!PHONE_PATTERN.matcher(phone).matches())
                rowErrors.add(err(displayRow, "phone", "Số điện thoại chứa ký tự không hợp lệ"));
        }

        if (code != null && !code.isBlank()) {
            if (code.length() > 50)
                rowErrors.add(err(displayRow, "employeeCode", "Mã nhân viên không được vượt quá 50 ký tự"));
            else if (!CODE_PATTERN.matcher(code).matches())
                rowErrors.add(err(displayRow, "employeeCode", "Mã nhân viên chỉ được chứa chữ, số, dấu gạch ngang và gạch dưới"));
            else if (codesSeenInBatch.contains(code))
                rowErrors.add(err(displayRow, "employeeCode", "Mã nhân viên bị trùng trong file import"));
            else if (employeeRepository.existsByTenantIdAndEmployeeCodeAndDeletedAtIsNull(tenantId, code))
                rowErrors.add(err(displayRow, "employeeCode", "Mã nhân viên ‘" + code + "’ đã tồn tại trong công ty"));
        }

        if (data.hiredDateText() != null && data.hiredDate() == null) {
            rowErrors.add(err(displayRow, "hiredDate",
                    "Ngày vào làm phải theo định dạng dd/MM/yyyy hoặc yyyy-MM-dd"));
        }

        if (data.position() != null && data.position().length() > 100) {
            rowErrors.add(err(displayRow, "position", "Chức vụ không được vượt quá 100 ký tự"));
        }

        if (data.department() != null && data.department().length() > 100) {
            rowErrors.add(err(displayRow, "department", "Phòng ban không được vượt quá 100 ký tự"));
        }

        return rowErrors;
    }

    /**
     * #41 gap fix (2026-08-16): re-runs the same row validation used by {@link #importEmployees}
     * against the same uploaded file, but instead of creating employees, builds a downloadable
     * .xlsx containing only the rows that failed plus their error reasons — the AC required an
     * "export lỗi" affordance that didn't exist before (errors were only ever returned as JSON).
     * Kept stateless/re-validate-on-request rather than persisting the previous import's errors,
     * since the import endpoint itself doesn't persist any import-run state today.
     */
    public byte[] exportImportErrors(UUID tenantId, MultipartFile file,
                                      UUID callerUserId, boolean callerIsPlatformAdmin) {
        assertImportAccess(tenantId, callerUserId, callerIsPlatformAdmin);
        ImportAnalysis analysis = analyzeImportFile(tenantId, file);

        try (Workbook out = new XSSFWorkbook()) {
            Sheet outSheet = out.createSheet("Dòng cần sửa");
            String[] cols = {"Dòng", "Mã nhân viên", "Họ và tên đệm", "Tên", "Email",
                    "Số điện thoại", "Chức vụ", "Phòng ban", "Ngày vào làm", "Chi tiết lỗi"};
            Row outHeader = outSheet.createRow(0);
            for (int c = 0; c < cols.length; c++) outHeader.createCell(c).setCellValue(cols[c]);

            int outRowNum = 1;
            for (EmployeeImportError structureError : analysis.structureErrors()) {
                Row outRow = outSheet.createRow(outRowNum++);
                outRow.createCell(0).setCellValue(structureError.getRow());
                outRow.createCell(9).setCellValue(structureError.getMessage());
            }
            for (AnalyzedImportRow analyzedRow : analysis.rows()) {
                if (analyzedRow.errors().isEmpty()) continue;
                ImportRowData data = analyzedRow.data();
                String joinedErrors = analyzedRow.errors().stream()
                        .map(e -> importFieldLabel(e.getField()) + ": " + e.getMessage())
                        .collect(java.util.stream.Collectors.joining("; "));

                Row outRow = outSheet.createRow(outRowNum++);
                int c = 0;
                outRow.createCell(c++).setCellValue(analyzedRow.rowNumber());
                outRow.createCell(c++).setCellValue(nullToEmpty(data.employeeCode()));
                outRow.createCell(c++).setCellValue(nullToEmpty(data.lastName()));
                outRow.createCell(c++).setCellValue(nullToEmpty(data.firstName()));
                outRow.createCell(c++).setCellValue(nullToEmpty(data.email()));
                outRow.createCell(c++).setCellValue(nullToEmpty(data.phone()));
                outRow.createCell(c++).setCellValue(nullToEmpty(data.position()));
                outRow.createCell(c++).setCellValue(nullToEmpty(data.department()));
                outRow.createCell(c++).setCellValue(nullToEmpty(data.hiredDateText()));
                outRow.createCell(c).setCellValue(joinedErrors);
            }

            for (int c = 0; c < cols.length; c++) {
                outSheet.setColumnWidth(c, c == 9 ? 70 * 256 : 20 * 256);
            }

            return toBytes(out);
        } catch (IOException e) {
            throw new IllegalStateException("Không thể tạo file tổng hợp lỗi import", e);
        }
    }

    private byte[] toBytes(Workbook workbook) throws IOException {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            workbook.write(bos);
            return bos.toByteArray();
        }
    }

    private static final DataFormatter DATA_FORMATTER = new DataFormatter();

    /** Maps an import-sheet header cell to the canonical English key the parser looks up.
     *  Accepts both the old English headers and the Vietnamese headers the export now uses
     *  (#export-readability) so an exported file can be edited and re-imported. */
    private static String canonicalImportHeader(String rawLowerTrimmed) {
        return switch (rawLowerTrimmed) {
            case "mã nhân viên", "mã nv", "ma nhan vien" -> "employeecode";
            case "tên", "ten" -> "firstname";
            case "họ và tên đệm", "họ và tên lót", "ho va ten dem" -> "lastname";
            case "email", "thư điện tử" -> "email";
            case "số điện thoại", "sđt", "so dien thoai" -> "phone";
            case "chức vụ", "chuc vu", "vị trí" -> "position";
            case "phòng ban", "phong ban", "bộ phận" -> "department";
            case "ngày vào làm", "ngay vao lam", "ngày tuyển" -> "hireddate";
            default -> rawLowerTrimmed;
        };
    }

    private String str(Row row, Integer colIdx) {
        if (colIdx == null) return null;
        Cell cell = row.getCell(colIdx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        String val = DATA_FORMATTER.formatCellValue(cell).trim();
        return val.isEmpty() ? null : val;
    }

    private String dateStr(Row row, Integer colIdx) {
        if (colIdx == null) return null;
        Cell cell = row.getCell(colIdx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        return str(row, colIdx);
    }

    private LocalDate parseImportDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value, VIETNAMESE_DATE_FORMAT);
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private String normalizedOrNull(String value, boolean lowercase) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return lowercase ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String importFieldLabel(String field) {
        return switch (field) {
            case "employeecode", "employeeCode" -> "Mã nhân viên";
            case "lastname", "lastName" -> "Họ và tên đệm";
            case "firstname", "firstName" -> "Tên";
            case "email" -> "Email";
            case "phone" -> "Số điện thoại";
            case "position" -> "Chức vụ";
            case "department" -> "Phòng ban";
            case "hireddate", "hiredDate" -> "Ngày vào làm";
            case "header" -> "Dòng tiêu đề";
            case "file" -> "File Excel";
            default -> field;
        };
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
        return toResponse(e, faceId, java.util.List.of());
    }

    public EmployeeResponse toResponse(Employee e, FaceIdStatusDto faceId, java.util.List<String> roleNames) {
        String plannedRoleName = e.getPlannedRoleId() != null
                ? roleRepository.findByIdAndDeletedAtIsNull(e.getPlannedRoleId()).map(Role::getName).orElse(null)
                : null;
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
                .plannedRoleId(e.getPlannedRoleId())
                .plannedRoleName(plannedRoleName)
                .nationalId(e.getNationalId())
                .status(e.getStatus())
                .terminatedAt(e.getTerminatedAt())
                .hiredDate(e.getHiredDate())
                .avatarUrl(e.getAvatarUrl())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .faceId(faceId)
                .piiMasked(!com.fams.shared.util.PiiAccess.currentCallerCanViewUnmaskedPii())
                .roleNames(roleNames)
                .build();
    }
}
