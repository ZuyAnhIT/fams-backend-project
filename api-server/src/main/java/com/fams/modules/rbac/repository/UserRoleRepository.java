package com.fams.modules.rbac.repository;

import com.fams.modules.rbac.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

    /** Every active role assignment in one tenant, across every role — powers the "Thành viên
     *  công ty" (Company Members) view, which is broader than the Employee list (covers anyone
     *  holding a role here, including an owner/admin never onboarded through HR). */
    @Query("SELECT ur FROM UserRole ur JOIN FETCH ur.role WHERE ur.tenantId = :tenantId AND ur.deletedAt IS NULL")
    List<UserRole> findAllWithRoleByTenantId(@Param("tenantId") UUID tenantId);

    /** Batch role lookup for a page of employees (avoids N+1) — used to show each employee's
     *  system role(s) inline in the employee list, see EmployeeService#listEmployees. */
    @Query("""
            SELECT ur FROM UserRole ur
            JOIN FETCH ur.role r
            WHERE ur.userId IN :userIds
              AND ur.tenantId = :tenantId
              AND ur.deletedAt IS NULL
            """)
    List<UserRole> findAllWithRoleByUserIdInAndTenantId(
            @Param("userIds") Collection<UUID> userIds,
            @Param("tenantId") UUID tenantId);

    @Query("""
            SELECT DISTINCT p.name
            FROM UserRole ur
            JOIN ur.role r
            JOIN r.permissions p
            WHERE ur.userId = :userId
              AND ur.tenantId = :tenantId
              AND ur.deletedAt IS NULL
            """)
    Set<String> findPermissionNamesByUserIdAndTenantId(
            @Param("userId") UUID userId,
            @Param("tenantId") UUID tenantId);

    /**
     * Issue #10 (docs/issues/ISSUES.md): permissions from platform-scoped role assignments
     * (roles.tenant_id IS NULL, e.g. PLATFORM_STAFF) — these apply regardless of which tenant
     * (if any) the current request/JWT is scoped to.
     */
    @Query("""
            SELECT DISTINCT p.name
            FROM UserRole ur
            JOIN ur.role r
            JOIN r.permissions p
            WHERE ur.userId = :userId
              AND ur.tenantId IS NULL
              AND ur.deletedAt IS NULL
            """)
    Set<String> findPlatformScopedPermissionNamesByUserId(@Param("userId") UUID userId);

    @Query("""
            SELECT ur FROM UserRole ur
            JOIN FETCH ur.role r
            WHERE ur.userId = :userId
              AND ur.deletedAt IS NULL
            ORDER BY ur.createdAt ASC
            """)
    List<UserRole> findAllActiveByUserId(@Param("userId") UUID userId);

    @Query("""
            SELECT ur FROM UserRole ur
            JOIN FETCH ur.role r
            WHERE ur.userId = :userId
              AND ur.tenantId = :tenantId
              AND ur.deletedAt IS NULL
            """)
    List<UserRole> findActiveByUserIdAndTenantId(
            @Param("userId") UUID userId,
            @Param("tenantId") UUID tenantId);

    boolean existsByRoleIdAndDeletedAtIsNull(UUID roleId);

    long countByRoleIdAndDeletedAtIsNull(UUID roleId);

    boolean existsByUserIdAndTenantIdAndDeletedAtIsNull(UUID userId, UUID tenantId);

    /** Used to evict every affected user's cached permission set after a role's permission
     *  list changes (see RoleService.updateRole) — without this, holders of an edited role
     *  keep acting on their stale cached permissions for up to the cache TTL. */
    List<UserRole> findByRoleIdAndDeletedAtIsNull(UUID roleId);

    /** Batch-counts active assignments per role for a page of roles (RoleService.listRoles) —
     *  lets the frontend show "N users hold this role" before attempting a delete, instead of
     *  discovering it only after a 400 from DELETE /roles/{id}. */
    @Query("SELECT ur.role.id AS roleId, COUNT(ur) AS cnt FROM UserRole ur "
            + "WHERE ur.role.id IN :roleIds AND ur.deletedAt IS NULL GROUP BY ur.role.id")
    List<RoleAssignmentCount> countActiveByRoleIdIn(@Param("roleIds") Collection<UUID> roleIds);

    /** Same as {@link #countActiveByRoleIdIn} but scoped to one tenant — required for SHARED
     *  system roles (TENANT_ADMIN, HR_MANAGER, SITE_SUPERVISOR, EMPLOYEE: one role row used by
     *  every tenant). Without this, a tenant admin viewing their own role list saw the count of
     *  that role's holders ACROSS THE ENTIRE PLATFORM, not just their own company — confirmed
     *  real bug, reported 2026-08-14. Tenant-owned custom roles don't strictly need this (their
     *  role.id already implies one tenant), but scoping is harmless there too. */
    @Query("SELECT ur.role.id AS roleId, COUNT(ur) AS cnt FROM UserRole ur "
            + "WHERE ur.role.id IN :roleIds AND ur.tenantId = :tenantId AND ur.deletedAt IS NULL GROUP BY ur.role.id")
    List<RoleAssignmentCount> countActiveByRoleIdInAndTenantId(
            @Param("roleIds") Collection<UUID> roleIds, @Param("tenantId") UUID tenantId);

    interface RoleAssignmentCount {
        UUID getRoleId();
        long getCnt();
    }

    /** Counts distinct people who currently hold ANY role granting `permissionName` in a
     *  tenant — spans every role (system or custom) that includes it, unlike
     *  {@link #countActiveByRoleIdInAndTenantId} which counts by a single role id. Used by
     *  UserRoleService's last-admin guard: before revoking a role that grants roles:update,
     *  confirm at least one other holder remains, so a tenant can never end up with nobody
     *  able to manage its own roles/members. */
    @Query("""
            SELECT COUNT(DISTINCT ur.userId) FROM UserRole ur
            JOIN ur.role r
            JOIN r.permissions p
            WHERE ur.tenantId = :tenantId
              AND p.name = :permissionName
              AND ur.deletedAt IS NULL
            """)
    long countDistinctActiveHoldersOfPermissionInTenant(
            @Param("tenantId") UUID tenantId, @Param("permissionName") String permissionName);

    /** #84 (2026-08-17): the actual userIds behind {@link #countDistinctActiveHoldersOfPermissionInTenant}
     *  — used to target missing-checkout notifications at every HR/admin holder of a given
     *  permission in a tenant, not just report a count. */
    @Query("""
            SELECT DISTINCT ur.userId FROM UserRole ur
            JOIN ur.role r
            JOIN r.permissions p
            WHERE ur.tenantId = :tenantId
              AND p.name = :permissionName
              AND ur.deletedAt IS NULL
            """)
    Set<UUID> findDistinctActiveHolderIdsOfPermissionInTenant(
            @Param("tenantId") UUID tenantId, @Param("permissionName") String permissionName);

    @Query("SELECT ur FROM UserRole ur JOIN FETCH ur.role WHERE ur.id = :id AND ur.deletedAt IS NULL")
    java.util.Optional<UserRole> findActiveById(@Param("id") UUID id);

    @Query("""
            SELECT ur FROM UserRole ur
            WHERE ur.userId = :userId
              AND ur.role.id = :roleId
              AND ur.tenantId = :tenantId
            ORDER BY ur.createdAt DESC
            """)
    List<UserRole> findByUserIdAndRoleIdAndTenantId(
            @Param("userId") UUID userId,
            @Param("roleId") UUID roleId,
            @Param("tenantId") UUID tenantId);

    /** Issue #10: dedup check for platform-scoped (tenant_id IS NULL) assignments. */
    @Query("""
            SELECT ur FROM UserRole ur
            WHERE ur.userId = :userId
              AND ur.role.id = :roleId
              AND ur.tenantId IS NULL
            ORDER BY ur.createdAt DESC
            """)
    List<UserRole> findByUserIdAndRoleIdAndTenantIdIsNull(
            @Param("userId") UUID userId,
            @Param("roleId") UUID roleId);
}
