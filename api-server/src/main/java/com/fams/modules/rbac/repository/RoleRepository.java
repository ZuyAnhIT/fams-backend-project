package com.fams.modules.rbac.repository;

import com.fams.modules.rbac.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID>, JpaSpecificationExecutor<Role> {

    Optional<Role> findByNameAndTenantIdIsNull(String name);

    List<Role> findByTenantIdAndDeletedAtIsNull(UUID tenantId);

    /** System-wide roles (tenantId null) plus this tenant's own custom roles — the full set of
     *  role names assignable within a given tenant, used to validate IP-whitelist role scoping. */
    List<Role> findByDeletedAtIsNullAndTenantIdIsNullOrDeletedAtIsNullAndTenantId(UUID tenantIdForCustomRoles);

    @Query("SELECT r FROM Role r JOIN FETCH r.permissions WHERE r.id = :id")
    Optional<Role> findByIdWithPermissions(@Param("id") UUID id);

    boolean existsByTenantIdAndNameAndDeletedAtIsNull(UUID tenantId, String name);

    Optional<Role> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByTenantIdAndNameAndDeletedAtIsNullAndIdNot(UUID tenantId, String name, UUID id);
}
