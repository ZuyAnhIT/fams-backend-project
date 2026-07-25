package com.fams.modules.rbac.service;

import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves a caller's effective site scope within a tenant, from their active role
 * assignments there (see {@link UserRole#getSiteIds()}).
 *
 * <p>Semantics mirror the tenant IP whitelist ({@code IpWhitelistGuard}): "unrestricted" is
 * the default and wins on conflict. A caller holding ANY assignment with an empty site set
 * (including Platform Admin, who never has a per-tenant assignment to check at all) has
 * unrestricted access to every site in the tenant — represented as {@link Optional#empty()}.
 * Only when EVERY one of their assignments in this tenant is site-scoped does the caller end
 * up restricted, to the union of all those sites — e.g. someone who is both TENANT_ADMIN
 * (unrestricted) and SITE_SUPERVISOR-for-site-X in the same tenant is still unrestricted,
 * same as a user's total access being the union of everything they hold.
 */
@Service
public class SiteScopeService {

    private final UserRoleRepository userRoleRepository;

    public SiteScopeService(UserRoleRepository userRoleRepository) {
        this.userRoleRepository = userRoleRepository;
    }

    /**
     * @return {@link Optional#empty()} if the caller is unrestricted (sees every site in the
     *         tenant); otherwise the exact set of site IDs they're allowed to see/act on. An
     *         empty-but-present set means "no sites at all" — e.g. a user with no active role
     *         assignment left in this tenant.
     */
    public Optional<Set<UUID>> resolveAllowedSiteIds(UUID userId, UUID tenantId, boolean isPlatformAdmin) {
        if (isPlatformAdmin) {
            return Optional.empty();
        }

        List<UserRole> assignments = userRoleRepository.findActiveByUserIdAndTenantId(userId, tenantId);
        boolean anyUnrestricted = assignments.stream().anyMatch(a -> a.getSiteIds().isEmpty());
        if (anyUnrestricted) {
            return Optional.empty();
        }

        Set<UUID> union = assignments.stream()
                .flatMap(a -> a.getSiteIds().stream())
                .collect(Collectors.toSet());
        return Optional.of(union);
    }

    /** Convenience wrapper for a single-site check (e.g. a URL path already carries {siteId}). */
    public boolean isSiteAllowed(UUID userId, UUID tenantId, UUID siteId, boolean isPlatformAdmin) {
        return resolveAllowedSiteIds(userId, tenantId, isPlatformAdmin)
                .map(allowed -> allowed.contains(siteId))
                .orElse(true);
    }
}
