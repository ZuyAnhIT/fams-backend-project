package com.fams.modules.workspace.service;

import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.workspace.dto.request.AssignWorkspaceMemberRequest;
import com.fams.modules.workspace.dto.request.TransferWorkspaceMemberRequest;
import com.fams.modules.workspace.dto.response.WorkspaceMemberResponse;
import com.fams.modules.workspace.entity.Workspace;
import com.fams.modules.workspace.entity.WorkspaceMember;
import com.fams.modules.workspace.repository.WorkspaceMemberRepository;
import com.fams.modules.workspace.repository.WorkspaceRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class WorkspaceMemberService {

    private final WorkspaceMemberRepository memberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final EmployeeRepository employeeRepository;
    private final TenantRepository tenantRepository;
    private final UserRoleRepository userRoleRepository;
    private final com.fams.modules.audit.service.AuditLogService auditLogService;

    public WorkspaceMemberService(WorkspaceMemberRepository memberRepository,
                                  WorkspaceRepository workspaceRepository,
                                  EmployeeRepository employeeRepository,
                                  TenantRepository tenantRepository,
                                  UserRoleRepository userRoleRepository,
                                  com.fams.modules.audit.service.AuditLogService auditLogService) {
        this.memberRepository = memberRepository;
        this.workspaceRepository = workspaceRepository;
        this.employeeRepository = employeeRepository;
        this.tenantRepository = tenantRepository;
        this.userRoleRepository = userRoleRepository;
        this.auditLogService = auditLogService;
    }

    private Map<String, Object> memberAuditSnapshot(WorkspaceMember m) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("workspaceId", m.getWorkspaceId().toString());
        snapshot.put("employeeId", m.getEmployeeId().toString());
        snapshot.put("role", m.getRole());
        snapshot.put("isPrimary", m.isPrimary());
        snapshot.put("effectiveFrom", m.getEffectiveFrom() != null ? m.getEffectiveFrom().toString() : null);
        return snapshot;
    }

    /** Demotes the employee's current primary membership (if any and if different from
     *  {@code exceptMemberId}) so at most one active primary remains — called before saving a
     *  new/transferred membership that should become primary. Uses saveAndFlush (not save):
     *  Hibernate's default flush order runs pending INSERTs before UPDATEs regardless of call
     *  order, so without an immediate flush here the new row's INSERT could hit the DB before
     *  this demotion's UPDATE, transiently violating the one-primary-per-employee partial unique
     *  index (confirmed live — this exact race threw a ConstraintViolationException). */
    private void demoteExistingPrimary(UUID tenantId, UUID employeeId, UUID exceptMemberId) {
        memberRepository.findByEmployeeIdAndTenantIdAndIsPrimaryTrueAndDeletedAtIsNull(employeeId, tenantId)
                .filter(existing -> !existing.getId().equals(exceptMemberId))
                .ifPresent(existing -> {
                    existing.setPrimary(false);
                    memberRepository.saveAndFlush(existing);
                });
    }

    @Transactional
    public WorkspaceMemberResponse assignMember(UUID tenantId, UUID workspaceId,
                                                AssignWorkspaceMemberRequest request,
                                                UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("workspace_members:create")) {
                throw new AccessDeniedException(
                        "You do not have permission to assign members to workspaces in this tenant");
            }
        }

        Workspace workspace = workspaceRepository.findByIdAndTenantIdAndDeletedAtIsNull(workspaceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found: " + workspaceId));

        // A deactivated workspace is being retired — mirrors the same "deactivation blocks new
        // assignment, existing members unaffected" rule already used for roles (see rbac-api.md).
        if (!"active".equals(workspace.getStatus())) {
            throw new IllegalArgumentException(
                    "Workspace '" + workspace.getName() + "' is inactive and can no longer accept new members");
        }

        employeeRepository.findByIdAndTenantIdAndDeletedAtIsNull(request.getEmployeeId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee not found: " + request.getEmployeeId()));

        if (memberRepository.existsByWorkspaceIdAndEmployeeIdAndDeletedAtIsNull(
                workspaceId, request.getEmployeeId())) {
            throw new DuplicateResourceException(
                    "Employee is already a member of this workspace");
        }

        String role = (request.getRole() != null) ? request.getRole() : "member";

        // #46 gap fix (2026-08-16): AC requires "chỉ một primary workspace active" — default to
        // primary only when the employee has no other active primary yet (first workspace becomes
        // primary automatically); an explicit true always wins and demotes whatever was primary
        // before, matching how "switching primary" should behave.
        boolean hasExistingPrimary = memberRepository
                .findByEmployeeIdAndTenantIdAndIsPrimaryTrueAndDeletedAtIsNull(request.getEmployeeId(), tenantId)
                .isPresent();
        boolean isPrimary = request.getIsPrimary() != null ? request.getIsPrimary() : !hasExistingPrimary;

        WorkspaceMember member = WorkspaceMember.builder()
                .workspaceId(workspaceId)
                .employeeId(request.getEmployeeId())
                .tenantId(tenantId)
                .role(role)
                .assignedBy(callerUserId)
                .isPrimary(isPrimary)
                .effectiveFrom(request.getEffectiveFrom() != null ? request.getEffectiveFrom() : LocalDate.now())
                .build();

        if (isPrimary) {
            demoteExistingPrimary(tenantId, request.getEmployeeId(), null);
        }

        memberRepository.save(member);
        log.info("Workspace member assigned: workspaceId={} employeeId={} role={} isPrimary={} tenantId={} by={}",
                workspaceId, request.getEmployeeId(), role, isPrimary, tenantId, callerUserId);

        try {
            auditLogService.record(
                    tenantId, callerUserId, null,
                    "WorkspaceMember", member.getId().toString(), "workspace_member_assigned",
                    null, memberAuditSnapshot(member),
                    com.fams.shared.security.HttpRequestUtils.currentRequestId(),
                    com.fams.shared.security.HttpRequestUtils.currentIpAddress(),
                    com.fams.shared.security.HttpRequestUtils.currentUserAgent());
        } catch (Exception e) {
            log.warn("Failed to record audit log for workspace member assign id={}: {}", member.getId(), e.getMessage());
        }

        return toResponse(member);
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkspaceMemberResponse> listMembers(UUID tenantId, UUID workspaceId,
                                                             int page, int size,
                                                             UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("workspace_members:list")) {
                throw new AccessDeniedException(
                        "You do not have permission to list workspace members in this tenant");
            }
        }

        workspaceRepository.findByIdAndTenantIdAndDeletedAtIsNull(workspaceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found: " + workspaceId));

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<WorkspaceMemberResponse> resultPage = memberRepository
                .findByWorkspaceIdAndTenantIdAndDeletedAtIsNull(workspaceId, tenantId, pageable)
                .map(this::toResponse);

        return PageResponse.from(resultPage);
    }

    @Transactional
    public void removeMember(UUID tenantId, UUID workspaceId, UUID memberId,
                             UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("workspace_members:delete")) {
                throw new AccessDeniedException(
                        "You do not have permission to remove members from workspaces in this tenant");
            }
        }

        workspaceRepository.findByIdAndTenantIdAndDeletedAtIsNull(workspaceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found: " + workspaceId));

        WorkspaceMember member = memberRepository
                .findByIdAndWorkspaceIdAndTenantIdAndDeletedAtIsNull(memberId, workspaceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found: " + memberId));

        Map<String, Object> before = memberAuditSnapshot(member);
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        member.setLeftAt(now);
        member.setDeletedAt(now);
        memberRepository.save(member);
        log.info("Workspace member removed: memberId={} workspaceId={} tenantId={} by={}",
                memberId, workspaceId, tenantId, callerUserId);

        try {
            auditLogService.record(
                    tenantId, callerUserId, null,
                    "WorkspaceMember", member.getId().toString(), "workspace_member_removed",
                    before, memberAuditSnapshot(member),
                    com.fams.shared.security.HttpRequestUtils.currentRequestId(),
                    com.fams.shared.security.HttpRequestUtils.currentIpAddress(),
                    com.fams.shared.security.HttpRequestUtils.currentUserAgent());
        } catch (Exception e) {
            log.warn("Failed to record audit log for workspace member remove id={}: {}", member.getId(), e.getMessage());
        }
    }

    @Transactional
    public WorkspaceMemberResponse transferMember(UUID tenantId, UUID sourceWorkspaceId, UUID memberId,
                                                  TransferWorkspaceMemberRequest request,
                                                  UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("workspace_members:create")
                    || !permissions.contains("workspace_members:delete")) {
                throw new AccessDeniedException(
                        "You do not have permission to transfer workspace members in this tenant");
            }
        }

        workspaceRepository.findByIdAndTenantIdAndDeletedAtIsNull(sourceWorkspaceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Source workspace not found: " + sourceWorkspaceId));

        if (request.getTargetWorkspaceId().equals(sourceWorkspaceId)) {
            throw new IllegalArgumentException(
                    "Target workspace must be different from the source workspace");
        }

        Workspace targetWorkspace = workspaceRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(request.getTargetWorkspaceId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Target workspace not found: " + request.getTargetWorkspaceId()));

        if (!"active".equals(targetWorkspace.getStatus())) {
            throw new IllegalArgumentException(
                    "Target workspace '" + targetWorkspace.getName()
                            + "' is inactive and can no longer accept new members");
        }

        WorkspaceMember existing = memberRepository
                .findByIdAndWorkspaceIdAndTenantIdAndDeletedAtIsNull(memberId, sourceWorkspaceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found: " + memberId));

        if (memberRepository.existsByWorkspaceIdAndEmployeeIdAndDeletedAtIsNull(
                request.getTargetWorkspaceId(), existing.getEmployeeId())) {
            throw new DuplicateResourceException(
                    "Employee is already a member of the target workspace");
        }

        Map<String, Object> before = memberAuditSnapshot(existing);

        // #47 gap fix (2026-08-16): close the source membership with leftAt (distinct from the
        // generic deletedAt soft-delete) so "left because transferred" is recorded explicitly,
        // matching the AC's left_at/effective_to requirement.
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        existing.setLeftAt(now);
        existing.setDeletedAt(now);
        boolean wasPrimary = existing.isPrimary();
        existing.setPrimary(false);
        memberRepository.save(existing);

        // Create membership in target workspace, inheriting role unless overridden
        String role = (request.getRole() != null) ? request.getRole() : existing.getRole();
        // isPrimary defaults to carrying over the flag from the membership being transferred — a
        // primary workspace stays primary after a transfer unless explicitly overridden.
        boolean isPrimary = request.getIsPrimary() != null ? request.getIsPrimary() : wasPrimary;
        LocalDate effectiveFrom = request.getEffectiveFrom() != null ? request.getEffectiveFrom() : LocalDate.now();

        WorkspaceMember newMembership = WorkspaceMember.builder()
                .workspaceId(request.getTargetWorkspaceId())
                .employeeId(existing.getEmployeeId())
                .tenantId(tenantId)
                .role(role)
                .assignedBy(callerUserId)
                .isPrimary(isPrimary)
                .effectiveFrom(effectiveFrom)
                .build();

        if (isPrimary) {
            demoteExistingPrimary(tenantId, existing.getEmployeeId(), null);
        }

        memberRepository.save(newMembership);
        log.info("Workspace member transferred: employeeId={} from={} to={} role={} isPrimary={} tenantId={} by={}",
                existing.getEmployeeId(), sourceWorkspaceId, request.getTargetWorkspaceId(),
                role, isPrimary, tenantId, callerUserId);

        try {
            auditLogService.record(
                    tenantId, callerUserId, null,
                    "WorkspaceMember", newMembership.getId().toString(), "workspace_member_transferred",
                    before, memberAuditSnapshot(newMembership),
                    com.fams.shared.security.HttpRequestUtils.currentRequestId(),
                    com.fams.shared.security.HttpRequestUtils.currentIpAddress(),
                    com.fams.shared.security.HttpRequestUtils.currentUserAgent());
        } catch (Exception e) {
            log.warn("Failed to record audit log for workspace member transfer id={}: {}", newMembership.getId(), e.getMessage());
        }

        return toResponse(newMembership);
    }

    private WorkspaceMemberResponse toResponse(WorkspaceMember m) {
        WorkspaceMemberResponse.EmployeeSummary employeeSummary = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(m.getEmployeeId(), m.getTenantId())
                .map(e -> WorkspaceMemberResponse.EmployeeSummary.builder()
                        .id(e.getId())
                        .employeeCode(e.getEmployeeCode())
                        .firstName(e.getFirstName())
                        .lastName(e.getLastName())
                        .fullName(e.getFirstName() + " " + e.getLastName())
                        .position(e.getPosition())
                        .email(e.getEmail())
                        .status(e.getStatus())
                        .build())
                .orElse(null);

        return WorkspaceMemberResponse.builder()
                .id(m.getId())
                .workspaceId(m.getWorkspaceId())
                .employeeId(m.getEmployeeId())
                .tenantId(m.getTenantId())
                .role(m.getRole())
                .employee(employeeSummary)
                .assignedBy(m.getAssignedBy())
                .isPrimary(m.isPrimary())
                .effectiveFrom(m.getEffectiveFrom())
                .leftAt(m.getLeftAt())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }
}
