package com.fams.modules.workspace.repository;

import com.fams.modules.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID>,
        JpaSpecificationExecutor<Workspace> {

    Optional<Workspace> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

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
