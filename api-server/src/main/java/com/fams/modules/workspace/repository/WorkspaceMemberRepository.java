package com.fams.modules.workspace.repository;

import com.fams.modules.workspace.entity.WorkspaceMember;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /** The employee's current primary membership (if any) — used to auto-demote the old primary
     *  when a new one is assigned/transferred in as primary. At most one such row can exist per
     *  (tenant, employee), enforced by a DB partial unique index (see V96 migration). */
    Optional<WorkspaceMember> findByEmployeeIdAndTenantIdAndIsPrimaryTrueAndDeletedAtIsNull(
            UUID employeeId, UUID tenantId);

    long countByWorkspaceIdAndDeletedAtIsNull(UUID workspaceId);

    /** #122-#125 (2026-08-18): reports workspace filter — attendance/violation entities have no
     *  workspaceId of their own (relationship is via WorkspaceMember), so the caller resolves
     *  this employee-ID set first, same pattern as
     *  AssignmentRepository#findDistinctEmployeeIdsByTenantIdAndSiteIdIn for the site filter. */
    @Query("SELECT DISTINCT m.employeeId FROM WorkspaceMember m "
            + "WHERE m.tenantId = :tenantId AND m.workspaceId = :workspaceId AND m.deletedAt IS NULL")
    java.util.Set<UUID> findDistinctEmployeeIdsByTenantIdAndWorkspaceId(
            @Param("tenantId") UUID tenantId, @Param("workspaceId") UUID workspaceId);

    /** Batch-counts active members per workspace for a page of workspaces
     *  (WorkspaceService.listWorkspaces) — avoids one query per row. */
    @Query("SELECT m.workspaceId AS workspaceId, COUNT(m) AS cnt FROM WorkspaceMember m "
            + "WHERE m.workspaceId IN :workspaceIds AND m.deletedAt IS NULL GROUP BY m.workspaceId")
    List<WorkspaceMemberCount> countActiveByWorkspaceIdIn(@Param("workspaceIds") Collection<UUID> workspaceIds);

    interface WorkspaceMemberCount {
        UUID getWorkspaceId();
        long getCnt();
    }
}
