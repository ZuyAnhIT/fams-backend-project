package com.fams.modules.rbac.service;

import com.fams.modules.rbac.dto.request.CreateRoleRequest;
import com.fams.modules.rbac.dto.request.UpdateRoleRequest;
import com.fams.modules.rbac.dto.response.PermissionResponse;
import com.fams.modules.rbac.dto.response.RoleDetailResponse;
import com.fams.modules.rbac.dto.response.RoleResponse;
import com.fams.modules.rbac.entity.Permission;
import com.fams.modules.rbac.entity.Role;
import com.fams.modules.rbac.repository.PermissionRepository;
import com.fams.modules.rbac.repository.RoleRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.specification.RoleSpecification;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.security.HttpRequestUtils;
import com.fams.shared.security.JwtAuthFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RoleService {

    private static final Set<String> SORTABLE_FIELDS = Set.of("name", "isSystem", "isActive", "createdAt", "updatedAt");

    private final RoleRepository roleRepository;
    private final TenantRepository tenantRepository;
    private final PermissionRepository permissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final StringRedisTemplate redis;
    private final AuditLogService auditLogService;

    public RoleService(RoleRepository roleRepository,
                       TenantRepository tenantRepository,
                       PermissionRepository permissionRepository,
                       UserRoleRepository userRoleRepository,
                       StringRedisTemplate redis,
                       AuditLogService auditLogService) {
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.permissionRepository = permissionRepository;
        this.userRoleRepository = userRoleRepository;
        this.redis = redis;
        this.auditLogService = auditLogService;
    }

    /** #31 (docs/api/backend-feature-audit-2026-08-07.md): role create/edit/delete changes
     *  what every holder of that role can do — a textbook compliance-audited action (SOC2/
     *  ISO 27001 access-control review) that was never traced to an actor before this fix.
     *  Best-effort, same non-blocking stance as every other AuditLogService call site. */
    private void recordRoleAudit(UUID tenantId, UUID actorId, String action, UUID roleId,
                                  Map<String, Object> oldValue, Map<String, Object> newValue) {
        try {
            auditLogService.record(
                    tenantId, actorId, null,
                    "Role", roleId.toString(), action,
                    oldValue, newValue,
                    HttpRequestUtils.currentRequestId(),
                    HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception ex) {
            log.warn("Failed to record audit log for {} roleId={}: {}", action, roleId, ex.getMessage());
        }
    }

    private Map<String, Object> roleAuditSnapshot(Role role) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", role.getName());
        m.put("description", role.getDescription());
        m.put("tenantId", role.getTenantId() != null ? role.getTenantId().toString() : null);
        m.put("isActive", role.isActive());
        m.put("permissions", role.getPermissions().stream().map(Permission::getName).sorted().toList());
        return m;
    }

    /** Evicts the cached permission set of every user currently holding this role, so an
     *  edited permission list takes effect immediately instead of waiting out the cache TTL
     *  (JwtAuthFilter caches per user+tenant for 5 minutes). Tenant-owned custom roles only
     *  ever have tenant-scoped assignments (assignRole enforces role.tenantId ==
     *  assignment.tenantId) — cache key uses the role's own tenantId. Platform-scoped custom
     *  roles (tenantId null) are assigned platform-wide, cache key uses the ":platform" suffix
     *  (see JwtAuthFilter/UserRoleService.evictPlatformPermissionCache). */
    private void evictPermissionCacheForRoleHolders(UUID roleId, UUID tenantId) {
        List<UUID> userIds = userRoleRepository.findByRoleIdAndDeletedAtIsNull(roleId).stream()
                .map(com.fams.modules.rbac.entity.UserRole::getUserId)
                .toList();
        String suffix = tenantId != null ? tenantId.toString() : "platform";
        for (UUID userId : userIds) {
            redis.delete(JwtAuthFilter.PERMS_CACHE_PREFIX + userId + ":" + suffix);
        }
        if (!userIds.isEmpty()) {
            log.info("Evicted permission cache for {} user(s) holding updated role {}", userIds.size(), roleId);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<RoleResponse> listRoles(
            UUID tenantId,
            String search,
            Boolean isSystem,
            Boolean isActive,
            String sortBy,
            String sortDir,
            int page,
            int size,
            UUID callerUserId,
            boolean callerIsPlatformAdmin) {

        if (tenantId != null) {
            tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

            // A non-admin caller may only list another tenant's custom roles if they actually
            // belong to it — without this check, any authenticated user could pass an arbitrary
            // tenantId and see that tenant's custom role names/descriptions/permission counts,
            // which is tenant-internal information (e.g. org structure) that shouldn't leak
            // across companies.
            if (!callerIsPlatformAdmin
                    && !userRoleRepository.existsByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId)) {
                throw new AccessDeniedException("You do not have permission to view roles in this tenant");
            }
        }

        String resolvedSortBy = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "isSystem";
        Sort sort = Sort.by(
                Sort.Order.desc("isSystem"),
                "asc".equalsIgnoreCase(sortDir)
                        ? Sort.Order.asc(resolvedSortBy)
                        : Sort.Order.desc(resolvedSortBy)
        );
        if ("isSystem".equals(resolvedSortBy)) {
            sort = Sort.by(Sort.Order.desc("isSystem"), Sort.Order.asc("name"));
        }

        // Platform-scoped custom roles (tenantId NULL, isSystem false) define FAMS's own
        // internal staff hierarchy (e.g. support tiers above/below PLATFORM_STAFF) — they are
        // NOT part of the public system-role catalogue every user sees, only Platform Admins
        // may know they exist. True system roles (isSystem true) remain visible to everyone,
        // same as before.
        Pageable pageable = PageRequest.of(page, size, sort);
        Specification<Role> spec = RoleSpecification.build(tenantId, search, isSystem, isActive, callerIsPlatformAdmin);
        Page<Role> rolePage = roleRepository.findAll(spec, pageable);

        Map<UUID, Long> assignmentCounts = batchLoadAssignmentCounts(
                rolePage.getContent().stream().map(Role::getId).toList());
        Page<RoleResponse> result = rolePage.map(role -> toResponse(role, assignmentCounts.getOrDefault(role.getId(), 0L)));

        log.info("List roles: tenantId={} search={} isSystem={} isActive={} page={} total={}",
                tenantId, search, isSystem, isActive, page, result.getTotalElements());

        return PageResponse.from(result);
    }

    @Transactional(readOnly = true)
    public RoleDetailResponse getRoleById(UUID roleId, UUID callerUserId, boolean callerIsPlatformAdmin) {
        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        if (!role.isSystem()) {
            if (role.getTenantId() != null) {
                if (!callerIsPlatformAdmin) {
                    UUID tenantId = role.getTenantId();
                    Set<String> callerPermissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
                    if (!callerPermissions.contains("roles:read") && !callerPermissions.contains("roles:update")) {
                        throw new AccessDeniedException("You do not have permission to view roles in this tenant");
                    }
                }
            } else if (!callerIsPlatformAdmin) {
                // Platform-scoped custom role — internal FAMS staff hierarchy, not visible
                // outside Platform Admin (see listRoles).
                throw new AccessDeniedException("You do not have permission to view this role");
            }
        }

        long assignmentCount = userRoleRepository.countByRoleIdAndDeletedAtIsNull(roleId);
        return toDetailResponse(role, assignmentCount);
    }

    @Transactional
    public RoleDetailResponse createRole(UUID callerUserId, boolean callerIsPlatformAdmin, CreateRoleRequest request) {
        UUID tenantId = request.getTenantId();

        if (tenantId == null) {
            // Platform-scoped custom role: defines FAMS's own internal staff hierarchy
            // (e.g. a tier above/below PLATFORM_STAFF) — deliberately Platform-Admin-only,
            // not delegable via a "roles:create" permission the way tenant roles are, since
            // this shapes the platform's own governance structure, not a customer's.
            if (!callerIsPlatformAdmin) {
                throw new AccessDeniedException("Only Platform Admins may create platform-scoped roles");
            }
        } else {
            tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

            if (!callerIsPlatformAdmin) {
                Set<String> callerPermissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
                if (!callerPermissions.contains("roles:create")) {
                    throw new AccessDeniedException("You do not have permission to create roles in this tenant");
                }
            }
        }

        if (roleRepository.existsByTenantIdAndNameAndDeletedAtIsNull(tenantId, request.getName())) {
            throw new DuplicateResourceException(
                    "Role name '" + request.getName() + "' already exists" + (tenantId != null ? " in tenant " + tenantId : " as a platform-scoped role"));
        }

        Set<Permission> permissions = new HashSet<>();
        if (request.getPermissionIds() != null && !request.getPermissionIds().isEmpty()) {
            List<Permission> found = permissionRepository.findAllById(request.getPermissionIds());
            if (found.size() != request.getPermissionIds().size()) {
                Set<UUID> foundIds = found.stream().map(Permission::getId).collect(Collectors.toSet());
                List<UUID> missing = request.getPermissionIds().stream()
                        .filter(id -> !foundIds.contains(id))
                        .toList();
                throw new ResourceNotFoundException("Permissions not found: " + missing);
            }
            permissions.addAll(found);
        }

        Role role = Role.builder()
                .tenantId(tenantId)
                .name(request.getName())
                .description(request.getDescription())
                .isSystem(false)
                .permissions(permissions)
                .build();

        role = roleRepository.save(role);

        log.info("Created custom role: id={} name={} tenantId={} permissionCount={} by userId={}",
                role.getId(), role.getName(), tenantId, permissions.size(), callerUserId);
        recordRoleAudit(tenantId, callerUserId, "role_created", role.getId(), null, roleAuditSnapshot(role));

        return toDetailResponse(role, 0L);
    }

    @Transactional
    public RoleDetailResponse updateRole(UUID roleId, UUID callerUserId, boolean callerIsPlatformAdmin,
                                         UpdateRoleRequest request) {

        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        if (role.isSystem()) {
            throw new IllegalArgumentException("System roles cannot be modified");
        }

        UUID tenantId = role.getTenantId();

        if (!callerIsPlatformAdmin) {
            if (tenantId == null) {
                // Platform-scoped custom role — no tenant to check a "roles:update" permission
                // against; only Platform Admin may touch the platform's own role hierarchy.
                throw new AccessDeniedException("Only Platform Admins may update platform-scoped roles");
            }
            Set<String> callerPermissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!callerPermissions.contains("roles:update")) {
                throw new AccessDeniedException("You do not have permission to update roles in this tenant");
            }
        }

        if (!role.getName().equals(request.getName()) &&
                roleRepository.existsByTenantIdAndNameAndDeletedAtIsNullAndIdNot(tenantId, request.getName(), roleId)) {
            throw new DuplicateResourceException(
                    "Role name '" + request.getName() + "' already exists in tenant " + tenantId);
        }

        Map<String, Object> before = roleAuditSnapshot(role);
        Set<Permission> permissions = new HashSet<>();
        if (!request.getPermissionIds().isEmpty()) {
            List<Permission> found = permissionRepository.findAllById(request.getPermissionIds());
            if (found.size() != request.getPermissionIds().size()) {
                Set<UUID> foundIds = found.stream().map(Permission::getId).collect(Collectors.toSet());
                List<UUID> missing = request.getPermissionIds().stream()
                        .filter(id -> !foundIds.contains(id))
                        .toList();
                throw new ResourceNotFoundException("Permissions not found: " + missing);
            }
            permissions.addAll(found);
        }

        role.setName(request.getName());
        role.setDescription(request.getDescription());
        role.getPermissions().clear();
        role.getPermissions().addAll(permissions);
        if (request.getIsActive() != null) {
            role.setActive(request.getIsActive());
        }

        role = roleRepository.save(role);
        evictPermissionCacheForRoleHolders(role.getId(), tenantId);

        log.info("Updated custom role: id={} name={} tenantId={} permissionCount={} by userId={}",
                role.getId(), role.getName(), tenantId, permissions.size(), callerUserId);
        recordRoleAudit(tenantId, callerUserId, "role_updated", role.getId(), before, roleAuditSnapshot(role));

        long assignmentCount = userRoleRepository.countByRoleIdAndDeletedAtIsNull(role.getId());
        return toDetailResponse(role, assignmentCount);
    }

    @Transactional
    public void deleteRole(UUID roleId, UUID callerUserId, boolean callerIsPlatformAdmin) {

        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        if (role.isSystem()) {
            throw new IllegalArgumentException("System roles cannot be deleted");
        }

        UUID tenantId = role.getTenantId();

        if (!callerIsPlatformAdmin) {
            if (tenantId == null) {
                throw new AccessDeniedException("Only Platform Admins may delete platform-scoped roles");
            }
            Set<String> callerPermissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!callerPermissions.contains("roles:delete")) {
                throw new AccessDeniedException("You do not have permission to delete roles in this tenant");
            }
        }

        if (userRoleRepository.existsByRoleIdAndDeletedAtIsNull(roleId)) {
            throw new IllegalArgumentException(
                    "Role is still assigned to one or more users. Revoke all assignments before deleting.");
        }

        Map<String, Object> before = roleAuditSnapshot(role);
        role.setDeletedAt(OffsetDateTime.now());
        roleRepository.save(role);

        log.info("Deleted custom role: id={} name={} tenantId={} by userId={}",
                role.getId(), role.getName(), tenantId, callerUserId);
        recordRoleAudit(tenantId, callerUserId, "role_deleted", role.getId(), before, null);
    }

    /** Batch-fetches active-assignment counts for a page of roles in one query, instead of
     *  one query per row — see UserRoleRepository.countActiveByRoleIdIn. */
    private Map<UUID, Long> batchLoadAssignmentCounts(List<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> counts = new java.util.HashMap<>();
        for (UserRoleRepository.RoleAssignmentCount row : userRoleRepository.countActiveByRoleIdIn(roleIds)) {
            counts.put(row.getRoleId(), row.getCnt());
        }
        return counts;
    }

    private RoleResponse toResponse(Role role, long assignmentCount) {
        return RoleResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .isSystem(role.isSystem())
                .isActive(role.isActive())
                .tenantId(role.getTenantId())
                .permissionCount(role.getPermissions().size())
                .assignmentCount(assignmentCount)
                .createdAt(role.getCreatedAt())
                .updatedAt(role.getUpdatedAt())
                .build();
    }

    private RoleDetailResponse toDetailResponse(Role role, long assignmentCount) {
        List<PermissionResponse> permResponses = role.getPermissions().stream()
                .map(p -> PermissionResponse.builder()
                        .id(p.getId())
                        .name(p.getName())
                        .resource(p.getResource())
                        .action(p.getAction())
                        .description(p.getDescription())
                        .build())
                .toList();

        return RoleDetailResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .isSystem(role.isSystem())
                .isActive(role.isActive())
                .tenantId(role.getTenantId())
                .permissions(permResponses)
                .permissionCount(permResponses.size())
                .assignmentCount(assignmentCount)
                .createdAt(role.getCreatedAt())
                .updatedAt(role.getUpdatedAt())
                .build();
    }
}
