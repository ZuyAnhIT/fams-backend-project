package com.fams.modules.rbac.service;

import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.rbac.dto.request.AssignRoleRequest;
import com.fams.modules.rbac.dto.response.MyRoleResponse;
import com.fams.modules.rbac.dto.response.UserRoleResponse;
import com.fams.modules.rbac.entity.Role;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.RoleRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.security.JwtAuthFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserRoleService {

    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final TenantRepository tenantRepository;
    private final StringRedisTemplate redis;

    public UserRoleService(UserRoleRepository userRoleRepository,
                           UserRepository userRepository,
                           RoleRepository roleRepository,
                           TenantRepository tenantRepository,
                           StringRedisTemplate redis) {
        this.userRoleRepository = userRoleRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.redis = redis;
    }

    private void evictPermissionCache(UUID userId, UUID tenantId) {
        redis.delete(JwtAuthFilter.PERMS_CACHE_PREFIX + userId + ":" + tenantId);
    }

    @Transactional(readOnly = true)
    public List<MyRoleResponse> getCurrentUserRoles(UUID userId) {
        return userRoleRepository.findAllActiveByUserId(userId).stream()
                .map(ur -> {
                    Role role = ur.getRole();
                    List<String> permissions = role.getPermissions().stream()
                            .map(p -> p.getName())
                            .sorted()
                            .collect(Collectors.toList());
                    return MyRoleResponse.builder()
                            .id(ur.getId())
                            .userId(ur.getUserId())
                            .roleId(role.getId())
                            .roleName(role.getName())
                            .tenantId(ur.getTenantId())
                            .assignedAt(ur.getCreatedAt())
                            .permissions(permissions)
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public UserRoleResponse assignRole(UUID callerUserId, boolean callerIsPlatformAdmin,
                                       AssignRoleRequest request) {
        UUID tenantId = request.getTenantId();
        UUID targetUserId = request.getUserId();
        UUID roleId = request.getRoleId();

        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> callerPermissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!callerPermissions.contains("roles:update")) {
                throw new AccessDeniedException("You do not have permission to assign roles in this tenant");
            }
        }

        userRepository.findByIdAndDeletedAtIsNull(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + targetUserId));

        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        // Role must be a system role OR belong to the target tenant
        if (role.getTenantId() != null && !role.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException(
                    "Role " + roleId + " does not belong to tenant " + tenantId);
        }

        // Check for existing assignment (active or soft-deleted)
        List<UserRole> existing = userRoleRepository
                .findByUserIdAndRoleIdAndTenantId(targetUserId, roleId, tenantId);

        if (!existing.isEmpty()) {
            UserRole latest = existing.get(0);
            if (latest.getDeletedAt() == null) {
                throw new DuplicateResourceException(
                        "User " + targetUserId + " already has role " + role.getName() + " in this tenant");
            }
            // Reactivate the soft-deleted assignment
            latest.setDeletedAt(null);
            latest.setAssignedBy(callerUserId);
            UserRole saved = userRoleRepository.save(latest);
            evictPermissionCache(targetUserId, tenantId);
            log.info("Reactivated role assignment: userRoleId={} userId={} roleId={} tenantId={} by={}",
                    saved.getId(), targetUserId, roleId, tenantId, callerUserId);
            return toResponse(saved);
        }

        UserRole assignment = UserRole.builder()
                .userId(targetUserId)
                .role(role)
                .tenantId(tenantId)
                .assignedBy(callerUserId)
                .build();

        assignment = userRoleRepository.save(assignment);
        evictPermissionCache(targetUserId, tenantId);
        log.info("Assigned role: userRoleId={} userId={} roleId={} tenantId={} by={}",
                assignment.getId(), targetUserId, roleId, tenantId, callerUserId);

        return toResponse(assignment);
    }

    @Transactional
    public void revokeRole(UUID userRoleId, UUID callerUserId, boolean callerIsPlatformAdmin) {

        UserRole assignment = userRoleRepository.findActiveById(userRoleId)
                .orElseThrow(() -> new ResourceNotFoundException("Active role assignment not found: " + userRoleId));

        UUID tenantId = assignment.getTenantId();

        if (!callerIsPlatformAdmin) {
            Set<String> callerPermissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!callerPermissions.contains("roles:update")) {
                throw new AccessDeniedException("You do not have permission to revoke roles in this tenant");
            }
        }

        assignment.setDeletedAt(OffsetDateTime.now());
        userRoleRepository.save(assignment);
        evictPermissionCache(assignment.getUserId(), tenantId);
        log.info("Revoked role assignment: userRoleId={} userId={} role={} tenantId={} by={}",
                userRoleId, assignment.getUserId(), assignment.getRole().getName(), tenantId, callerUserId);
    }

    private UserRoleResponse toResponse(UserRole ur) {
        return UserRoleResponse.builder()
                .id(ur.getId())
                .userId(ur.getUserId())
                .roleId(ur.getRole().getId())
                .roleName(ur.getRole().getName())
                .tenantId(ur.getTenantId())
                .assignedBy(ur.getAssignedBy())
                .createdAt(ur.getCreatedAt())
                .updatedAt(ur.getUpdatedAt())
                .build();
    }
}
