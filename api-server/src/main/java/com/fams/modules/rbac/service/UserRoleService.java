package com.fams.modules.rbac.service;

import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.rbac.dto.request.AssignPlatformRoleRequest;
import com.fams.modules.rbac.dto.request.AssignRoleRequest;
import com.fams.modules.rbac.dto.request.BulkAssignRoleRequest;
import com.fams.modules.rbac.dto.response.BulkAssignRoleResponse;
import com.fams.modules.rbac.dto.response.MyRoleResponse;
import com.fams.modules.rbac.dto.response.RoleMemberResponse;
import com.fams.modules.rbac.dto.response.SiteRefResponse;
import com.fams.modules.rbac.dto.response.UserRoleResponse;
import com.fams.modules.rbac.constant.RoleEventTypes;
import com.fams.modules.rbac.entity.Role;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.RoleRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.notification.service.NotificationService;
import com.fams.shared.exception.BusinessException;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.security.HttpRequestUtils;
import com.fams.shared.security.JwtAuthFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    public UserRoleService(UserRoleRepository userRoleRepository,
                           UserRepository userRepository,
                           RoleRepository roleRepository,
                           TenantRepository tenantRepository,
                           SiteRepository siteRepository,
                           StringRedisTemplate redis,
                           AuditLogService auditLogService,
                           NotificationService notificationService) {
        this.userRoleRepository = userRoleRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.siteRepository = siteRepository;
        this.redis = redis;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
    }

    /** A notification failure must never break the role assign/revoke flow that triggered it —
     *  same defensive pattern as RandomCheckDispatchService's own notification call. */
    private void notifySafely(UUID tenantId, UUID userId, String eventType, String title, String body,
                               Map<String, Object> metadata) {
        try {
            notificationService.createNotification(tenantId, userId, eventType, title, body, metadata);
        } catch (Exception ex) {
            log.warn("Failed to send {} notification to userId={}: {}", eventType, userId, ex.getMessage());
        }
    }

    /** #31 (docs/api/backend-feature-audit-2026-08-07.md): granting/revoking who holds a role
     *  is the other half of RBAC's audit trail (see RoleService#recordRoleAudit for the role-
     *  definition half) — "who can do X" is meaningless for compliance review without "who
     *  gave/removed that access and when". tenantId is null for platform-scoped assignments,
     *  same convention TotpService already uses for account-level (not tenant-level) events. */
    private void recordUserRoleAudit(UUID tenantId, UUID actorId, String action, UUID userRoleId,
                                      Map<String, Object> oldValue, Map<String, Object> newValue) {
        try {
            auditLogService.record(
                    tenantId, actorId, null,
                    "UserRole", userRoleId.toString(), action,
                    oldValue, newValue,
                    HttpRequestUtils.currentRequestId(),
                    HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception ex) {
            log.warn("Failed to record audit log for {} userRoleId={}: {}", action, userRoleId, ex.getMessage());
        }
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

    /**
     * A tenant's owner (tenants.owner_id) should never lose access to their own company just
     * because their role assignment was removed — ownership is a separate, permanent
     * relationship from role assignments (see docs/reviews/backend/
     * rbac-role-permission-audit-2026-08-13.md mục 10). Confirmed as a REAL, reproducible
     * self-lockout during 2026-08-15 manual QA: an owner with zero active roles in their own
     * tenant disappeared entirely from the "select company" screen despite owner_id being
     * untouched, with no recovery path short of a direct DB fix. Called on login, tenant
     * switch, and whenever "my roles" is queried, so this state is healed transparently
     * before we ever compute what the caller can see/do — re-granting TENANT_ADMIN is a
     * no-op if they already hold an active role in every tenant they own.
     */
    @Transactional
    public void selfHealOwnerRoles(UUID userId) {
        User owner = userRepository.findByIdAndDeletedAtIsNull(userId).orElse(null);
        if (owner == null || owner.isPlatformAdmin()) {
            // Platform administration and company membership are separate security scopes.
            // Never recreate a tenant role for an internal admin account from legacy data.
            return;
        }
        List<Tenant> ownedTenants = tenantRepository.findAllByOwnerIdAndDeletedAtIsNull(userId);
        if (ownedTenants.isEmpty()) {
            return;
        }
        for (Tenant tenant : ownedTenants) {
            if (userRoleRepository.existsByUserIdAndTenantIdAndDeletedAtIsNull(userId, tenant.getId())) {
                continue;
            }
            Role tenantAdminRole = roleRepository.findByNameAndTenantIdIsNull("TENANT_ADMIN").orElse(null);
            if (tenantAdminRole == null) {
                log.warn("Cannot self-heal owner role for userId={} tenantId={}: TENANT_ADMIN role missing",
                        userId, tenant.getId());
                continue;
            }

            // uq_user_roles is (user_id, role_id, tenant_id) WITHOUT excluding soft-deleted
            // rows — if this owner previously held (and lost) TENANT_ADMIN here, a plain
            // INSERT would violate that constraint. Reactivate the existing row instead,
            // exactly like assignRole's own reactivate-or-create pattern.
            List<UserRole> existing = userRoleRepository
                    .findByUserIdAndRoleIdAndTenantId(userId, tenantAdminRole.getId(), tenant.getId());
            UserRole healed;
            if (!existing.isEmpty()) {
                healed = existing.get(0);
                healed.setDeletedAt(null);
                healed.setAssignedBy(userId);
                healed = userRoleRepository.save(healed);
            } else {
                healed = UserRole.builder()
                        .userId(userId)
                        .role(tenantAdminRole)
                        .tenantId(tenant.getId())
                        .assignedBy(userId)
                        .siteIds(new HashSet<>())
                        .build();
                healed = userRoleRepository.save(healed);
            }
            evictPermissionCache(userId, tenant.getId());
            log.warn("Self-healed missing owner role: userId={} tenantId={} userRoleId={}",
                    userId, tenant.getId(), healed.getId());
            recordUserRoleAudit(tenant.getId(), userId, "role_self_healed", healed.getId(), null,
                    userRoleAuditSnapshot(healed));
        }
    }

    @Transactional
    public List<MyRoleResponse> getCurrentUserRoles(UUID userId) {
        selfHealOwnerRoles(userId);
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

    /**
     * Lists who currently holds a given role — previously the only visibility into this was a
     * bare count ("27 người") on the role list, with no way to see who without opening each
     * employee's profile one at a time (2026-08-14 user feedback). Access rules mirror
     * {@link com.fams.modules.rbac.service.RoleService#getRoleById}: a tenant-owned custom
     * role is scoped to its own tenant automatically; a platform-tier system role
     * (PLATFORM_ADMIN/PLATFORM_STAFF, see V91) or platform-scoped custom role is Platform-Admin
     * only; a tenant-TIER system role (TENANT_ADMIN/HR_MANAGER/SITE_SUPERVISOR/EMPLOYEE) is
     * shared across every tenant, so the caller must specify WHICH tenant's holders they want
     * and must actually belong to it (or be Platform Admin) — without this, any authenticated
     * user could list every TENANT_ADMIN across the entire platform by role name alone.
     */
    @Transactional(readOnly = true)
    public List<RoleMemberResponse> listRoleMembers(UUID roleId, UUID requestedTenantId,
                                                     UUID callerUserId, boolean callerIsPlatformAdmin) {
        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        UUID scopeTenantId;
        if (role.getTenantId() != null) {
            scopeTenantId = role.getTenantId();
            if (!callerIsPlatformAdmin) {
                Set<String> callerPermissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, scopeTenantId);
                if (!callerPermissions.contains("roles:read") && !callerPermissions.contains("roles:update")) {
                    throw new AccessDeniedException("You do not have permission to view roles in this tenant");
                }
            }
        } else if (role.isPlatformRole() || !role.isSystem()) {
            if (!callerIsPlatformAdmin) {
                throw new AccessDeniedException("You do not have permission to view this role");
            }
            scopeTenantId = null;
        } else {
            if (requestedTenantId == null) {
                throw new IllegalArgumentException("tenantId is required to view members of a system role");
            }
            if (!callerIsPlatformAdmin
                    && !userRoleRepository.existsByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, requestedTenantId)) {
                throw new AccessDeniedException("You do not have permission to view roles in this tenant");
            }
            scopeTenantId = requestedTenantId;
        }

        List<UserRole> assignments = userRoleRepository.findByRoleIdAndDeletedAtIsNull(roleId).stream()
                .filter(ur -> scopeTenantId == null || scopeTenantId.equals(ur.getTenantId()))
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();

        Map<UUID, User> usersById = userRepository.findAllById(
                assignments.stream().map(UserRole::getUserId).collect(Collectors.toSet())
        ).stream().collect(Collectors.toMap(User::getId, Function.identity()));

        return assignments.stream()
                .map(ur -> {
                    User u = usersById.get(ur.getUserId());
                    return RoleMemberResponse.builder()
                            .userRoleId(ur.getId())
                            .userId(ur.getUserId())
                            .displayName(u != null ? u.getDisplayName() : null)
                            .contact(u != null ? (u.getEmail() != null ? u.getEmail() : u.getPhone()) : null)
                            .assignedAt(ur.getCreatedAt())
                            .siteIds(new ArrayList<>(ur.getSiteIds()))
                            .build();
                })
                .toList();
    }

    @Transactional
    public UserRoleResponse assignRole(UUID callerUserId, boolean callerIsPlatformAdmin,
                                       AssignRoleRequest request) {
        UUID tenantId = request.getTenantId();
        UUID targetUserId = request.getUserId();
        UUID roleId = request.getRoleId();

        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> callerPermissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!callerPermissions.contains("roles:update")) {
                throw new AccessDeniedException("You do not have permission to assign roles in this tenant");
            }
        }

        User targetUser = userRepository.findByIdAndDeletedAtIsNull(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + targetUserId));
        if (targetUser.isPlatformAdmin()) {
            throw new IllegalArgumentException(
                    "A Platform Admin account cannot be assigned a company role. "
                            + "Use a separate company user account.");
        }

        Role role = roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        // Role must be a system role OR belong to the target tenant
        if (role.getTenantId() != null && !role.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException(
                    "Role " + roleId + " does not belong to tenant " + tenantId);
        }

        // Platform-scoped roles (tenantId null) — whether a custom platform role, or the two
        // platform-tier SYSTEM roles PLATFORM_ADMIN/PLATFORM_STAFF (isPlatformRole=true, see
        // V91) — define FAMS's own internal staff hierarchy and must go through
        // assignPlatformRole, never through a tenant context. SECURITY FIX (2026-08-14): the
        // isPlatformRole half of this check was missing before — role.isSystem()==true made
        // PLATFORM_ADMIN/PLATFORM_STAFF slip past the old "!role.isSystem()" condition
        // entirely, so any tenant admin with roles:update could grant themselves or anyone
        // else full PLATFORM_ADMIN permissions inside their own tenant. Confirmed exploitable
        // live before this fix.
        if (role.getTenantId() == null && (!role.isSystem() || role.isPlatformRole())) {
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
            recordUserRoleAudit(tenantId, callerUserId, "role_assigned", saved.getId(), null,
                    userRoleAuditSnapshot(saved));
            notifyRoleAssigned(tenantId, tenant.getName(), targetUserId, role.getName());
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
        recordUserRoleAudit(tenantId, callerUserId, "role_assigned", assignment.getId(), null,
                userRoleAuditSnapshot(assignment));
        notifyRoleAssigned(tenantId, tenant.getName(), targetUserId, role.getName());

        return toResponse(assignment);
    }

    private void notifyRoleAssigned(UUID tenantId, String tenantName, UUID targetUserId, String roleName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", tenantId.toString());
        metadata.put("roleName", roleName);
        notifySafely(tenantId, targetUserId, RoleEventTypes.ROLE_ASSIGNED,
                "Bạn được gán vai trò mới",
                "Bạn vừa được gán vai trò \"" + roleName + "\" trong " + tenantName + ".",
                metadata);
    }

    /**
     * Assigns one role to many users in a single call — the practical fix for "move everyone
     * off the retiring role onto its replacement" without clicking through each person one at
     * a time. Reuses {@link #assignRole} per user (identical validation/behavior to a single
     * assignment) rather than duplicating its rules; each user is isolated in its own
     * try/catch so one bad entry (already has the role, user not found, ...) doesn't roll back
     * everyone else already processed in the batch.
     */
    @Transactional
    public BulkAssignRoleResponse bulkAssignRole(UUID callerUserId, boolean callerIsPlatformAdmin,
                                                 BulkAssignRoleRequest request) {
        UUID tenantId = request.getTenantId();

        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> callerPermissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!callerPermissions.contains("roles:update")) {
                throw new AccessDeniedException("You do not have permission to assign roles in this tenant");
            }
        }

        List<BulkAssignRoleResponse.Result> results = new ArrayList<>();
        for (UUID userId : request.getUserIds()) {
            try {
                if (request.getRevokeRoleId() != null) {
                    revokeRoleIfHeld(userId, request.getRevokeRoleId(), tenantId, callerUserId, callerIsPlatformAdmin);
                }

                AssignRoleRequest single = new AssignRoleRequest();
                single.setTenantId(tenantId);
                single.setUserId(userId);
                single.setRoleId(request.getRoleId());
                single.setSiteIds(request.getSiteIds());

                UserRoleResponse assigned = assignRole(callerUserId, callerIsPlatformAdmin, single);
                results.add(BulkAssignRoleResponse.Result.builder()
                        .userId(userId).success(true).userRoleId(assigned.getId()).build());
            } catch (Exception ex) {
                log.warn("Bulk role assignment failed for userId={} tenantId={} roleId={}: {}",
                        userId, tenantId, request.getRoleId(), ex.getMessage());
                results.add(BulkAssignRoleResponse.Result.builder()
                        .userId(userId).success(false).message(ex.getMessage()).build());
            }
        }

        long successCount = results.stream().filter(BulkAssignRoleResponse.Result::isSuccess).count();
        log.info("Bulk role assignment complete: tenantId={} roleId={} total={} success={} failed={} by={}",
                tenantId, request.getRoleId(), results.size(), successCount, results.size() - successCount, callerUserId);

        return BulkAssignRoleResponse.builder()
                .results(results)
                .successCount((int) successCount)
                .failureCount((int) (results.size() - successCount))
                .build();
    }

    /** Best-effort companion to bulkAssignRole's revokeRoleId — silently no-ops if the user
     *  never held that role (nothing to revoke isn't an error in a bulk "move everyone" call).
     *  Still subject to the last-admin guard: if this would strip the tenant's last
     *  roles:update holder, it throws (caught per-user by bulkAssignRole's own try/catch, so
     *  it surfaces as one failed row rather than aborting the whole batch). */
    private void revokeRoleIfHeld(UUID userId, UUID roleId, UUID tenantId, UUID callerUserId,
                                   boolean callerIsPlatformAdmin) {
        userRoleRepository.findByUserIdAndRoleIdAndTenantId(userId, roleId, tenantId).stream()
                .filter(ur -> ur.getDeletedAt() == null)
                .findFirst()
                .ifPresent(ur -> {
                    assertNotLastAdminHolder(ur, callerIsPlatformAdmin);
                    Map<String, Object> before = userRoleAuditSnapshot(ur);
                    ur.setDeletedAt(OffsetDateTime.now());
                    userRoleRepository.save(ur);
                    evictPermissionCache(userId, tenantId);
                    recordUserRoleAudit(tenantId, callerUserId, "role_revoked", ur.getId(), before, null);
                });
    }

    /**
     * Prevents revoking the last remaining `roles:update` holder in a tenant — without this,
     * a single revoke could strip every admin capability from a company, leaving nobody able
     * to manage roles/members ever again. Confirmed as a REAL, reproducible self-lockout
     * during 2026-08-15 manual QA (see docs/reviews/backend/
     * rbac-role-permission-audit-2026-08-13.md mục 10): revoking a tenant's last TENANT_ADMIN
     * succeeded silently and left the tenant with no visible membership at all. Platform
     * Admins are exempt — they're the intended recovery channel for genuinely stuck tenants.
     */
    private void assertNotLastAdminHolder(UserRole assignment, boolean callerIsPlatformAdmin) {
        if (callerIsPlatformAdmin || assignment.getTenantId() == null) {
            return;
        }
        boolean grantsRoleManagement = assignment.getRole().getPermissions().stream()
                .anyMatch(p -> "roles:update".equals(p.getName()));
        if (!grantsRoleManagement) {
            return;
        }
        long remainingHolders = userRoleRepository.countDistinctActiveHoldersOfPermissionInTenant(
                assignment.getTenantId(), "roles:update");
        if (remainingHolders <= 1) {
            throw new BusinessException(
                    "LAST_ADMIN_ROLE",
                    "Không thể thu hồi role này vì đây là quyền quản trị vai trò cuối cùng của công ty — "
                            + "hãy gán quyền quản trị cho người khác trước khi thu hồi role này.",
                    HttpStatus.CONFLICT,
                    "Refusing to revoke the last roles:update holder in tenant " + assignment.getTenantId());
        }
    }

    private Map<String, Object> userRoleAuditSnapshot(UserRole ur) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("userId", ur.getUserId().toString());
        m.put("roleId", ur.getRole().getId().toString());
        m.put("roleName", ur.getRole().getName());
        m.put("siteIds", ur.getSiteIds().stream().map(UUID::toString).sorted().toList());
        return m;
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
            recordUserRoleAudit(null, callerUserId, "platform_role_assigned", saved.getId(), null,
                    userRoleAuditSnapshot(saved));
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
        recordUserRoleAudit(null, callerUserId, "platform_role_assigned", assignment.getId(), null,
                userRoleAuditSnapshot(assignment));

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

        assertNotLastAdminHolder(assignment, callerIsPlatformAdmin);

        Map<String, Object> before = userRoleAuditSnapshot(assignment);
        assignment.setDeletedAt(OffsetDateTime.now());
        userRoleRepository.save(assignment);
        if (tenantId == null) {
            evictPlatformPermissionCache(assignment.getUserId());
        } else {
            evictPermissionCache(assignment.getUserId(), tenantId);
        }
        log.info("Revoked role assignment: userRoleId={} userId={} role={} tenantId={} by={}",
                userRoleId, assignment.getUserId(), assignment.getRole().getName(), tenantId, callerUserId);
        recordUserRoleAudit(tenantId, callerUserId, "role_revoked", userRoleId, before, null);

        if (tenantId != null) {
            String tenantName = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                    .map(Tenant::getName).orElse("công ty");
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("tenantId", tenantId.toString());
            metadata.put("roleName", assignment.getRole().getName());
            notifySafely(tenantId, assignment.getUserId(), RoleEventTypes.ROLE_REVOKED,
                    "Vai trò của bạn đã bị thu hồi",
                    "Vai trò \"" + assignment.getRole().getName() + "\" của bạn trong " + tenantName
                            + " đã bị thu hồi.",
                    metadata);
        }
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
