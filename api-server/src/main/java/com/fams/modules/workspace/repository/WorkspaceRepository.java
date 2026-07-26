package com.fams.modules.workspace.repository;

import com.fams.modules.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID>,
        JpaSpecificationExecutor<Workspace> {

    Optional<Workspace> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    long countByParentIdAndDeletedAtIsNull(UUID parentId);

    /** Batch-counts active child workspaces per parent for a page of workspaces
     *  (WorkspaceService.listWorkspaces) — avoids one query per row. */
    @Query("SELECT w.parentId AS parentId, COUNT(w) AS cnt FROM Workspace w "
            + "WHERE w.parentId IN :parentIds AND w.deletedAt IS NULL GROUP BY w.parentId")
    List<WorkspaceChildCount> countActiveChildrenByParentIdIn(@Param("parentIds") Collection<UUID> parentIds);

    interface WorkspaceChildCount {
        UUID getParentId();
        long getCnt();
    }

    @Query("SELECT COUNT(w) > 0 FROM Workspace w WHERE w.tenantId = :tenantId " +
           "AND lower(w.name) = lower(:name) AND w.deletedAt IS NULL")
    boolean existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(
            @Param("tenantId") UUID tenantId, @Param("name") String name);

    @Query("SELECT COUNT(w) > 0 FROM Workspace w WHERE w.tenantId = :tenantId " +
           "AND lower(w.name) = lower(:name) AND w.deletedAt IS NULL AND w.id <> :excludeId")
    boolean existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNullAndIdNot(
            @Param("tenantId") UUID tenantId, @Param("name") String name, @Param("excludeId") UUID excludeId);

    List<Workspace> findByTenantIdAndDeletedAtIsNullOrderByNameAsc(UUID tenantId);
}
