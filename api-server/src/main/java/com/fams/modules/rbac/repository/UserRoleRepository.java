package com.fams.modules.rbac.repository;

import com.fams.modules.rbac.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

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
}
