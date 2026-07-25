package com.fams.modules.workspace.repository;

import com.fams.modules.workspace.entity.WorkspaceMember;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

    boolean existsByWorkspaceIdAndEmployeeIdAndDeletedAtIsNull(UUID workspaceId, UUID employeeId);

    Page<WorkspaceMember> findByWorkspaceIdAndTenantIdAndDeletedAtIsNull(
            UUID workspaceId, UUID tenantId, Pageable pageable);

    Optional<WorkspaceMember> findByIdAndWorkspaceIdAndTenantIdAndDeletedAtIsNull(
            UUID id, UUID workspaceId, UUID tenantId);

    /** Used by EmployeeService.getEmployee to show every workspace this employee belongs to
     *  on the employee detail screen. */
    List<WorkspaceMember> findByEmployeeIdAndTenantIdAndDeletedAtIsNull(UUID employeeId, UUID tenantId);
}
