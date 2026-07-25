package com.fams.modules.rbac.service;

import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.rbac.dto.request.AssignPlatformRoleRequest;
import com.fams.modules.rbac.dto.request.AssignRoleRequest;
import com.fams.modules.rbac.dto.response.MyRoleResponse;
import com.fams.modules.rbac.dto.response.SiteRefResponse;
import com.fams.modules.rbac.dto.response.UserRoleResponse;
import com.fams.modules.rbac.entity.Role;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.RoleRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.tenant.entity.Tenant;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserRoleService {

    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final TenantRepository tenantRepository;
    private final SiteRepository siteRepository;
    private final StringRedisTemplate redis;

    public UserRoleService(UserRoleRepository userRoleRepository,
                           UserRepository userRepository,
                           RoleRepository roleRepository,
                           TenantRepository tenantRepository,
                           SiteRepository siteRepository,
                           StringRedisTemplate redis) {
        this.userRoleRepository = userRoleRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.siteRepository = siteRepository;
        this.redis = redis;
    }

    /** Validates that every requested site actually belongs to this tenant (and isn't
     *  deleted) before it can be used to restrict a role assignment. */
    private Set<UUID> resolveAndValidateSiteIds(List<UUID> requestedSiteIds, UUID tenantId) {
        if (requestedSiteIds == null || requestedSiteIds.isEmpty()) {
            // Must be mutable — this gets assigned straight into a Hibernate-managed
            // @ElementCollection (UserRole.siteIds); an immutable Set.of() blows up with
            // UnsupportedOperationException the moment Hibernate tries to reconcile it.
            return new HashSet<>();
        }
        Set<UUID> requested = new HashSet<>(requestedSiteIds);
        List<Site> found = siteRepository.findAllById(requested);
        Set<UUID> validIds = found.stream()
                .filter(s -> tenantId.equals(s.getTenantId()) && s.getDeletedAt() == null)
                .map(Site::getId)
                .collect(Collectors.toSet());
        if (!validIds.equals(requested)) {
            Set<UUID> invalid = new HashSet<>(requested);
            invalid.removeAll(validIds);
            throw new ResourceNotFoundException("Site(s) not found in this tenant: " + invalid);
        }
        return requested;
    }

    private void evictPermissionCache(UUID userId, UUID tenantId) {
        redis.delete(JwtAuthFilter.PERMS_CACHE_PREFIX + userId + ":" + tenantId);
    }

    private void evictPlatformPermissionCache(UUID userId) {
        redis.delete(JwtAuthFilter.PERMS_CACHE_PREFIX + userId + ":platform");
    }

    @Transactional(readOnly = true)
    public List<MyRoleResponse> getCurrentUserRoles(UUID userId) {
        List<UserRole> assignments = userRoleRepository.findAllActiveByUserId(userId);

        // Issue #3 (docs/issues/ISSUES.md): a user may hold roles across several tenants
        // (multi-tenant membership) — batch-fetch tenant names/slugs so the frontend can build
        // a "switch company" list from this single call instead of one lookup per tenant.
        Set<UUID> tenantIds = assignments.stream()
                .map(UserRole::getTenantId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<UUID, Tenant> tenantsById = tenantRepository.findAllByIdInAndDeletedAtIsNull(tenantIds).stream()
                .collect(Collectors.toMap(Tenant::getId, Function.identity()));

        // Same batching principle for site names: union every siteId across every assignment
        // (possibly several tenants) into a single query, so the FE "Quyền của tôi" screen
        // never has to follow up with GET /sites just to turn an id into a label.
        Set<UUID> allSiteIds = assignments.stream()
                .flatMap(ur -> ur.getSiteIds().stream())
                .collect(Collectors.toSet());
        Map<UUID, String> siteNamesById = resolveSiteNames(allSiteIds);

        return assignments.stream()
                .map(ur -> {
                    Role role = ur.getRole();
                    List<String> permissions = role.getPermissions().stream()
                            .map(p -> p.getName())
                            .sorted()
                            .collect(Collectors.toList());
                    Tenant tenant = ur.getTenantId() != null ? tenantsById.get(ur.getTenantId()) : null;
                    return MyRoleResponse.builder()
                            .id(ur.getId())
                            .userId(ur.getUserId())
                            .roleId(role.getId())
                            .roleName(role.getName())
                            .tenantId(ur.getTenantId())
                            .tenantName(tenant != null ? tenant.getName() : null)
                            .tenantSlug(tenant != null ? tenant.getSlug() : null)
                            .assignedAt(ur.getCreatedAt())
                            .permissions(permissions)
                            .siteIds(new ArrayList<>(ur.getSiteIds()))
                            .sites(toSiteRefs(ur.getSiteIds(), siteNamesById))
                            .build();
                })
                .collect(Collectors.toList());
    }

    private Map<UUID, String> resolveSiteNames(Set<UUID> siteIds) {
        if (siteIds.isEmpty()) {
            return Map.of();
        }
        return siteRepository.findAllById(siteIds).stream()
                .collect(Collectors.toMap(Site::getId, Site::getName));
    }

    private List<SiteRefResponse> toSiteRefs(Set<UUID> siteIds, Map<UUID, String> siteNamesById) {
        return siteIds.stream()
                .map(id -> SiteRefResponse.builder().id(id).name(siteNamesById.get(id)).build())
                .toList();
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

        // Platform-scoped custom roles (tenantId null, not system) define FAMS's own internal
        // staff hierarchy — they must go through assignPlatformRole, never through a tenant
        // context. Without this check they'd slip past the check above (null tenantId always
        // "matches") and a tenant admin could effectively hand out a platform-governance role.
        if (role.getTenantId() == null && !role.isSystem()) {
            throw new IllegalArgumentException(
                    "Role '" + role.getName() + "' is a platform-scoped role and can only be assigned via "
                    + "/user-roles/platform, not within a tenant");
        }

        // Deactivated custom roles are retired from new assignments — existing holders are
        // unaffected (deactivation never touches user_roles), but nobody new can be granted it.
        if (!role.isSystem() && !role.isActive()) {
            throw new IllegalArgumentException(
                    "Role '" + role.getName() + "' has been deactivated and can no longer be assigned");
        }

        Set<UUID> siteIds = resolveAndValidateSiteIds(request.getSiteIds(), tenantId);

        // Check for existing assignment (active or soft-deleted)
        List<UserRole> existing = userRoleRepository
                .findByUserIdAndRoleIdAndTenantId(targetUserId, roleId, tenantId);

        if (!existing.isEmpty()) {
            UserRole latest = existing.get(0);
            if (latest.getDeletedAt() == null) {
                throw new DuplicateResourceException(
                        "User " + targetUserId + " already has role " + role.getName() + " in this tenant");
            }
            // Reactivate the soft-deleted assignment — treat this as a fresh grant, so the
            // site scope reflects what was just requested rather than whatever it was before.
            latest.setDeletedAt(null);
            latest.setAssignedBy(callerUserId);
            latest.setSiteIds(siteIds);
            UserRole saved = userRoleRepository.save(latest);
            evictPermissionCache(targetUserId, tenantId);
            log.info("Reactivated role assignment: userRoleId={} userId={} roleId={} tenantId={} siteIds={} by={}",
                    saved.getId(), targetUserId, roleId, tenantId, siteIds, callerUserId);
            return toResponse(saved);
        }

        UserRole assignment = UserRole.builder()
                .userId(targetUserId)
                .role(role)
                .tenantId(tenantId)
                .assignedBy(callerUserId)
                .siteIds(siteIds)
                .build();

        assignment = userRoleRepository.save(assignment);
        evictPermissionCache(targetUserId, tenantId);
        log.info("Assigned role: userRoleId={} userId={} roleId={} tenantId={} siteIds={} by={}",
                assignment.getId(), targetUserId, roleId, tenantId, siteIds, callerUserId);

        return toResponse(assignment);
    }

    /**
     * Issue #10 (docs/issues/ISSUES.md): assigns a platform-scoped (tenant_id IS NULL) role —
     * a built-in system role such as PLATFORM_STAFF, or (since 25/07/2026) a platform-scoped
     * CUSTOM role created via POST /roles with no tenantId, used to build an internal staff
     * hierarchy above/below PLATFORM_STAFF. Restricted to Platform Admins at the controller
     * (@PreAuthorize) — granting platform-wide operational access is a platform-level decision,
     * not something any tenant admin can do (unlike ordinary tenant-scoped role assignment).
     */
    @Transactional
    public UserRoleResponse assignPlatformRole(UUID callerUserId, AssignPlatformRoleRequest request) {
        UUID targetUserId = request.getUserId();
        UUID roleId = request.getRoleId();

        userRepository.findByIdAndDeletedAtIsNull(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + targetUserId));

        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));
        if (!role.isSystem() && !role.isActive()) {
            throw new IllegalArgumentException(
                    "Role '" + role.getName() + "' has been deactivated and can no longer be assigned");
        }
        if (role.getTenantId() != null) {
            throw new IllegalArgumentException(
                    "Role " + role.getName() + " is tenant-owned and cannot be assigned as a platform-scoped role");
        }

        List<UserRole> existing = userRoleRepository.findByUserIdAndRoleIdAndTenantIdIsNull(targetUserId, roleId);
        if (!existing.isEmpty()) {
            UserRole latest = existing.get(0);
            if (latest.getDeletedAt() == null) {
                throw new DuplicateResourceException(
                        "User " + targetUserId + " already has platform role " + role.getName());
            }
            latest.setDeletedAt(null);
            latest.setAssignedBy(callerUserId);
            UserRole saved = userRoleRepository.save(latest);
            evictPlatformPermissionCache(targetUserId);
            log.info("Reactivated platform role assignment: userRoleId={} userId={} roleId={} by={}",
                    saved.getId(), targetUserId, roleId, callerUserId);
            return toResponse(saved);
        }

        UserRole assignment = UserRole.builder()
                .userId(targetUserId)
                .role(role)
                .tenantId(null)
                .assignedBy(callerUserId)
                .build();

        assignment = userRoleRepository.save(assignment);
        evictPlatformPermissionCache(targetUserId);
        log.info("Assigned platform role: userRoleId={} userId={} roleId={} by={}",
                assignment.getId(), targetUserId, roleId, callerUserId);

        return toResponse(assignment);
    }

    @Transactional
    public void revokeRole(UUID userRoleId, UUID callerUserId, boolean callerIsPlatformAdmin) {

        UserRole assignment = userRoleRepository.findActiveById(userRoleId)
                .orElseThrow(() -> new ResourceNotFoundException("Active role assignment not found: " + userRoleId));

        UUID tenantId = assignment.getTenantId();

        if (!callerIsPlatformAdmin) {
            // Platform-scoped assignments (tenantId == null) can only ever be revoked by
            // Platform Admins — there is no tenant to check a "roles:update" permission against.
            if (tenantId == null) {
                throw new AccessDeniedException("Only Platform Admins can revoke a platform-scoped role");
            }
            Set<String> callerPermissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!callerPermissions.contains("roles:update")) {
                throw new AccessDeniedException("You do not have permission to revoke roles in this tenant");
            }
        }

        assignment.setDeletedAt(OffsetDateTime.now());
        userRoleRepository.save(assignment);
        if (tenantId == null) {
            evictPlatformPermissionCache(assignment.getUserId());
        } else {
            evictPermissionCache(assignment.getUserId(), tenantId);
        }
        log.info("Revoked role assignment: userRoleId={} userId={} role={} tenantId={} by={}",
                userRoleId, assignment.getUserId(), assignment.getRole().getName(), tenantId, callerUserId);
    }

    private UserRoleResponse toResponse(UserRole ur) {
        Map<UUID, String> siteNamesById = resolveSiteNames(ur.getSiteIds());
        return UserRoleResponse.builder()
                .id(ur.getId())
                .userId(ur.getUserId())
                .roleId(ur.getRole().getId())
                .roleName(ur.getRole().getName())
                .tenantId(ur.getTenantId())
                .siteIds(new ArrayList<>(ur.getSiteIds()))
                .sites(toSiteRefs(ur.getSiteIds(), siteNamesById))
                .assignedBy(ur.getAssignedBy())
                .createdAt(ur.getCreatedAt())
                .updatedAt(ur.getUpdatedAt())
                .build();
    }
}
