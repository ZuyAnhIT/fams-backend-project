package com.fams.modules.tenant.service;

import com.fams.modules.rbac.entity.Role;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.RoleRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.tenant.dto.request.CreateIpWhitelistRequest;
import com.fams.modules.tenant.dto.request.UpdateIpWhitelistRequest;
import com.fams.modules.tenant.dto.response.IpWhitelistResponse;
import com.fams.modules.tenant.entity.TenantIpWhitelist;
import com.fams.modules.tenant.repository.TenantIpWhitelistRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.tenant.util.IpCidrMatcher;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class IpWhitelistService {

    private final TenantRepository tenantRepository;
    private final TenantIpWhitelistRepository whitelistRepository;
    private final IpWhitelistGuard ipWhitelistGuard;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;

    public IpWhitelistService(TenantRepository tenantRepository,
                              TenantIpWhitelistRepository whitelistRepository,
                              IpWhitelistGuard ipWhitelistGuard,
                              RoleRepository roleRepository,
                              UserRoleRepository userRoleRepository) {
        this.tenantRepository = tenantRepository;
        this.whitelistRepository = whitelistRepository;
        this.ipWhitelistGuard = ipWhitelistGuard;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<IpWhitelistResponse> listEntries(UUID tenantId, UUID userId, boolean isPlatformAdmin, int page, int size) {
        assertAccess(tenantId, userId, isPlatformAdmin);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt")));
        Page<TenantIpWhitelist> entries = whitelistRepository.findByTenantIdAndDeletedAtIsNull(tenantId, pageable);
        return PageResponse.from(entries.map(this::toResponse));
    }

    @Transactional
    public IpWhitelistResponse addEntry(UUID tenantId, CreateIpWhitelistRequest request,
                                        UUID userId, boolean isPlatformAdmin) {
        assertAccess(tenantId, userId, isPlatformAdmin);

        Set<String> roleNames = validateRoleNames(tenantId, request.getApplicableRoleNames());

        TenantIpWhitelist entry = TenantIpWhitelist.builder()
                .tenantId(tenantId)
                .ipAddress(request.getIpAddress().trim())
                .label(request.getLabel())
                .applicableRoleNames(roleNames)
                .build();

        assertCallerNotLockedOut(tenantId, userId, null, entry.getIpAddress(), true, roleNames, isPlatformAdmin);

        whitelistRepository.save(entry);
        ipWhitelistGuard.invalidate(tenantId);
        log.info("IP whitelist entry added: tenantId={} ip={} by userId={}", tenantId, entry.getIpAddress(), userId);

        return toResponse(entry);
    }

    @Transactional
    public IpWhitelistResponse updateEntry(UUID tenantId, UUID entryId, UpdateIpWhitelistRequest request,
                                           UUID userId, boolean isPlatformAdmin) {
        assertAccess(tenantId, userId, isPlatformAdmin);

        TenantIpWhitelist entry = whitelistRepository.findByIdAndTenantIdAndDeletedAtIsNull(entryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("IP whitelist entry not found: " + entryId));

        if (request.getLabel() != null)    entry.setLabel(request.getLabel().isBlank() ? null : request.getLabel());
        if (request.getApplicableRoleNames() != null) {
            entry.setApplicableRoleNames(validateRoleNames(tenantId, request.getApplicableRoleNames()));
        }
        if (request.getIsActive() != null) entry.setActive(request.getIsActive());

        assertCallerNotLockedOut(tenantId, userId, entryId, entry.getIpAddress(), entry.isActive(),
                entry.getApplicableRoleNames(), isPlatformAdmin);

        whitelistRepository.save(entry);
        ipWhitelistGuard.invalidate(tenantId);
        log.info("IP whitelist entry updated: id={} tenantId={} by userId={}", entryId, tenantId, userId);

        return toResponse(entry);
    }

    @Transactional
    public void deleteEntry(UUID tenantId, UUID entryId, UUID userId, boolean isPlatformAdmin) {
        assertAccess(tenantId, userId, isPlatformAdmin);

        TenantIpWhitelist entry = whitelistRepository.findByIdAndTenantIdAndDeletedAtIsNull(entryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("IP whitelist entry not found: " + entryId));

        assertCallerNotLockedOut(tenantId, userId, entryId, null, false, Set.of(), isPlatformAdmin);

        entry.setDeletedAt(OffsetDateTime.now());
        whitelistRepository.save(entry);
        ipWhitelistGuard.invalidate(tenantId);
        log.info("IP whitelist entry deleted: id={} tenantId={} by userId={}", entryId, tenantId, userId);
    }

    /** Empty/null input = applies to every role (stored as an empty set). Non-empty input is
     *  validated against the tenant's actually-assignable roles (system roles + this tenant's
     *  own custom roles) so a typo doesn't silently create an entry nobody's role ever matches. */
    private Set<String> validateRoleNames(UUID tenantId, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Set.of();
        }
        Set<String> validNames = roleRepository
                .findByDeletedAtIsNullAndTenantIdIsNullOrDeletedAtIsNullAndTenantId(tenantId)
                .stream().map(Role::getName).collect(java.util.stream.Collectors.toSet());
        Set<String> requestedSet = new HashSet<>(requested);
        requestedSet.removeIf(String::isBlank);
        Set<String> unknown = new HashSet<>(requestedSet);
        unknown.removeAll(validNames);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown role name(s) for this tenant: " + unknown);
        }
        return requestedSet;
    }

    /**
     * Safety net matching how real systems handle IP allow lists (AWS security groups,
     * GitHub org IP allow list, etc.): refuse a change that would leave the CALLER's own
     * current IP outside the resulting active set — otherwise a self-service owner can lock
     * themselves out of the very endpoint they'd need to fix it. Only a Platform Admin could
     * then recover the tenant (JwtAuthFilter/IpWhitelistGuard exempts admins from enforcement
     * entirely), so this check only applies to non-admin callers.
     *
     * Role-aware (2026-08-13): only entries that would actually apply to the CALLER's own
     * role count toward the "resulting active" baseline — an entry scoped to a role the caller
     * doesn't hold can never lock the caller out, so it's correctly ignored here.
     *
     * @param excludeEntryId entry being updated/deleted (excluded from the "existing active"
     *                       baseline so its OLD state isn't double-counted) — null when adding
     * @param candidateIp the entry's ip_address after this change would take effect — null
     *                    when deleting (no replacement)
     * @param candidateActive whether that candidate entry would be active after this change
     * @param candidateRoleNames the entry's role scope after this change would take effect
     */
    private void assertCallerNotLockedOut(UUID tenantId, UUID callerId, UUID excludeEntryId,
                                          String candidateIp, boolean candidateActive,
                                          Set<String> candidateRoleNames, boolean isPlatformAdmin) {
        if (isPlatformAdmin) {
            return; // admins bypass enforcement entirely — never at risk from this
        }
        String callerIp = HttpRequestUtils.currentIpAddress();
        if (callerIp == null) {
            return; // can't evaluate — fail open, matching IpWhitelistGuard's own fail-open rule
        }

        List<UserRole> callerRoles = userRoleRepository.findAllActiveByUserId(callerId);
        String callerRoleName = callerRoles.isEmpty() ? null : callerRoles.get(0).getRole().getName();

        List<String> resultingActive = new ArrayList<>();
        whitelistRepository.findByTenantIdAndIsActiveTrueAndDeletedAtIsNull(tenantId).forEach(e -> {
            boolean sameEntry = excludeEntryId != null && e.getId().equals(excludeEntryId);
            boolean appliesToCaller = e.getApplicableRoleNames().isEmpty()
                    || (callerRoleName != null && e.getApplicableRoleNames().contains(callerRoleName));
            if (!sameEntry && appliesToCaller) {
                resultingActive.add(e.getIpAddress());
            }
        });
        boolean candidateAppliesToCaller = candidateRoleNames.isEmpty()
                || (callerRoleName != null && candidateRoleNames.contains(callerRoleName));
        if (candidateActive && candidateIp != null && candidateAppliesToCaller) {
            resultingActive.add(candidateIp);
        }

        if (resultingActive.isEmpty()) {
            return; // tenant would become unrestricted for the caller's own role — always safe
        }
        boolean callerStillCovered = resultingActive.stream().anyMatch(ip -> IpCidrMatcher.matches(callerIp, ip));
        if (!callerStillCovered) {
            throw new IllegalArgumentException(
                    "This change would remove your own current IP (" + callerIp + ") from the whitelist and lock "
                    + "you out. Keep or add an entry that covers your current IP, or ask a Platform Admin to make "
                    + "this change instead.");
        }
    }

    private void assertAccess(UUID tenantId, UUID userId, boolean isPlatformAdmin) {
        var tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!isPlatformAdmin && !userId.equals(tenant.getOwnerId())) {
            throw new AccessDeniedException("You do not have permission to manage this tenant's IP whitelist");
        }
    }

    private IpWhitelistResponse toResponse(TenantIpWhitelist e) {
        return IpWhitelistResponse.builder()
                .id(e.getId())
                .tenantId(e.getTenantId())
                .ipAddress(e.getIpAddress())
                .label(e.getLabel())
                .applicableRoleNames(new ArrayList<>(e.getApplicableRoleNames()))
                .isActive(e.isActive())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
