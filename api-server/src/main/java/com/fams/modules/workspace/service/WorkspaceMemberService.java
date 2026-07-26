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

    public WorkspaceMemberService(WorkspaceMemberRepository memberRepository,
                                  WorkspaceRepository workspaceRepository,
                                  EmployeeRepository employeeRepository,
                                  TenantRepository tenantRepository,
                                  UserRoleRepository userRoleRepository) {
        this.memberRepository = memberRepository;
        this.workspaceRepository = workspaceRepository;
        this.employeeRepository = employeeRepository;
        this.tenantRepository = tenantRepository;
        this.userRoleRepository = userRoleRepository;
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

        WorkspaceMember member = WorkspaceMember.builder()
                .workspaceId(workspaceId)
                .employeeId(request.getEmployeeId())
                .tenantId(tenantId)
                .role(role)
                .assignedBy(callerUserId)
                .build();

        memberRepository.save(member);
        log.info("Workspace member assigned: workspaceId={} employeeId={} role={} tenantId={} by={}",
                workspaceId, request.getEmployeeId(), role, tenantId, callerUserId);
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

        member.setDeletedAt(java.time.OffsetDateTime.now());
        memberRepository.save(member);
        log.info("Workspace member removed: memberId={} workspaceId={} tenantId={} by={}",
                memberId, workspaceId, tenantId, callerUserId);
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

        // Soft-delete the source membership
        existing.setDeletedAt(java.time.OffsetDateTime.now());
        memberRepository.save(existing);

        // Create membership in target workspace, inheriting role unless overridden
        String role = (request.getRole() != null) ? request.getRole() : existing.getRole();
        WorkspaceMember newMembership = WorkspaceMember.builder()
                .workspaceId(request.getTargetWorkspaceId())
                .employeeId(existing.getEmployeeId())
                .tenantId(tenantId)
                .role(role)
                .assignedBy(callerUserId)
                .build();

        memberRepository.save(newMembership);
        log.info("Workspace member transferred: employeeId={} from={} to={} role={} tenantId={} by={}",
                existing.getEmployeeId(), sourceWorkspaceId, request.getTargetWorkspaceId(),
                role, tenantId, callerUserId);
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
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }
}
