package com.fams.modules.tenant.service;

import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.rbac.entity.Role;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.RoleRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.subscription.entity.TenantSubscription;
import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import com.fams.modules.subscription.entity.TenantSubscription.SubscriptionStatus;
import com.fams.modules.subscription.entity.Plan;
import com.fams.modules.subscription.repository.PlanRepository;
import com.fams.modules.subscription.repository.TenantSubscriptionRepository;
import com.fams.modules.tenant.dto.request.CreateTenantRequest;
import com.fams.modules.tenant.dto.request.TransferOwnerRequest;
import com.fams.modules.tenant.dto.request.UpdateTenantRequest;
import com.fams.modules.tenant.dto.response.TenantResponse;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.tenant.specification.TenantSpecification;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.security.HttpRequestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TenantService {

    public static final String TENANT_SUSPENDED_PREFIX = "tenant:suspended:";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PlanRepository planRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final StringRedisTemplate redis;
    private final AuditLogService auditLogService;

    public TenantService(TenantRepository tenantRepository, UserRepository userRepository,
                         PlanRepository planRepository,
                         TenantSubscriptionRepository subscriptionRepository,
                         RoleRepository roleRepository,
                         UserRoleRepository userRoleRepository,
                         StringRedisTemplate redis,
                         AuditLogService auditLogService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.redis = redis;
        this.auditLogService = auditLogService;
    }

    /** #31 (docs/api/backend-feature-audit-2026-08-07.md): tenant lifecycle actions are
     *  platform-admin-level and compliance-sensitive (who suspended/created/renamed a
     *  customer's tenant) but were never audited, unlike every other mutation-heavy module.
     *  Best-effort like every other call site in the codebase — an audit-log failure must
     *  never roll back the real tenant change. */
    private void recordTenantAudit(UUID tenantId, UUID actorId, String action,
                                    Map<String, Object> oldValue, Map<String, Object> newValue) {
        try {
            auditLogService.record(
                    tenantId, actorId, null,
                    "Tenant", tenantId.toString(), action,
                    oldValue, newValue,
                    HttpRequestUtils.currentRequestId(),
                    HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception ex) {
            log.warn("Failed to record audit log for {} tenantId={}: {}", action, tenantId, ex.getMessage());
        }
    }

    private Map<String, Object> tenantAuditSnapshot(Tenant tenant) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", tenant.getName());
        m.put("slug", tenant.getSlug());
        m.put("domain", tenant.getDomain());
        m.put("industry", tenant.getIndustry());
        m.put("countryCode", tenant.getCountryCode());
        m.put("timezone", tenant.getTimezone());
        m.put("locale", tenant.getLocale());
        m.put("currencyCode", tenant.getCurrencyCode());
        m.put("status", tenant.getStatus());
        m.put("ownerId", tenant.getOwnerId() != null ? tenant.getOwnerId().toString() : null);
        return m;
    }

    /**
     * Two distinct creation paths, matching how real multi-tenant SaaS platforms (Slack,
     * Notion, HubSpot...) separate self-serve signup from platform-side provisioning:
     *
     * <p><b>Self-service</b> ({@code canProvisionForOthers=false}): any authenticated user
     * creates their OWN company. They become its TENANT_ADMIN automatically, the tenant
     * starts in {@code trial} status on the default (lowest-cost/free) plan, and none of
     * {@code ownerUserId}/{@code ownerEmail}/{@code planId} may be set — a regular user
     * cannot assign someone else as owner or pick a paid plan for free.
     *
     * <p><b>Platform provisioning</b> ({@code canProvisionForOthers=true} — caller is a
     * Platform Admin or holds the {@code tenants:create} permission, e.g. Platform Staff):
     * the caller MUST name an existing FAMS user (by {@code ownerUserId} or
     * {@code ownerEmail}) to become the tenant's owner/TENANT_ADMIN — this is a direct
     * assignment, not an invitation, because the person is required to already have an
     * account. The tenant starts {@code active} (not trial). Every tenant — self-service or
     * platform-provisioned — is always assigned the lowest-cost/default (trial) plan at
     * creation; online payment isn't built yet, so no creation path may pick a paid plan.
     * Upgrading a tenant to a paid plan is a separate, deliberate action via
     * {@code PATCH /tenants/{id}/subscription}. The provisioning caller is NOT made a
     * member of the tenant themselves and, per product decision, loses the ability to edit
     * the tenant's profile once created — see {@link #updateTenant} — ongoing management
     * belongs solely to the assigned owner.
     */
    @Transactional
    public TenantResponse createTenant(CreateTenantRequest request, UUID createdByUserId, boolean canProvisionForOthers) {
        if (tenantRepository.findBySlugAndDeletedAtIsNull(request.getSlug()).isPresent()) {
            throw new DuplicateResourceException("Slug '" + request.getSlug() + "' is already taken");
        }

        if (StringUtils.hasText(request.getDomain())
                && tenantRepository.findByDomainAndDeletedAtIsNull(request.getDomain()).isPresent()) {
            throw new DuplicateResourceException("Domain '" + request.getDomain() + "' is already registered");
        }

        UUID ownerId;

        if (canProvisionForOthers) {
            if (request.getOwnerUserId() == null && !StringUtils.hasText(request.getOwnerEmail())) {
                throw new IllegalArgumentException(
                        "ownerUserId or ownerEmail is required — the assigned owner must already have a FAMS account");
            }
            ownerId = resolveExistingOwner(request).getId();
        } else {
            if (request.getOwnerUserId() != null || StringUtils.hasText(request.getOwnerEmail())) {
                throw new AccessDeniedException(
                        "Only Platform Admins/Staff (tenants:create) may assign an owner at creation");
            }
            ownerId = createdByUserId;
        }

        Tenant tenant = Tenant.builder()
                .name(request.getName())
                .slug(request.getSlug())
                .domain(StringUtils.hasText(request.getDomain()) ? request.getDomain() : null)
                .industry(request.getIndustry())
                .countryCode(request.getCountryCode())
                .timezone(StringUtils.hasText(request.getTimezone()) ? request.getTimezone() : "UTC")
                .locale(StringUtils.hasText(request.getLocale()) ? request.getLocale() : "en")
                .currencyCode(StringUtils.hasText(request.getCurrencyCode()) ? request.getCurrencyCode() : "USD")
                .status(canProvisionForOthers ? "active" : "trial")
                .ownerId(ownerId)
                .build();

        tenantRepository.save(tenant);
        log.info("Tenant created: id={} slug={} ownerId={} by userId={} provisioned={}",
                tenant.getId(), tenant.getSlug(), ownerId, createdByUserId, canProvisionForOthers);

        // Every new tenant always starts on the default (lowest sortOrder) plan — no
        // creation path may pick a paid plan since online payment isn't built yet. Upgrading
        // is a deliberate, separate action via PATCH /tenants/{id}/subscription.
        Plan planToAssign = planRepository.findByIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc()
                .stream().findFirst().orElse(null);

        if (planToAssign != null) {
            TenantSubscription sub = TenantSubscription.builder()
                    .tenantId(tenant.getId())
                    .planId(planToAssign.getId())
                    .status(SubscriptionStatus.TRIAL)
                    .billingCycle(BillingCycle.MONTHLY)
                    .build();
            subscriptionRepository.save(sub);
            log.info("Subscription created: tenantId={} planId={} status=TRIAL", tenant.getId(), planToAssign.getId());
        } else {
            log.warn("No active plan found — tenant {} created without a subscription", tenant.getId());
        }

        Role tenantAdminRole = roleRepository.findByNameAndTenantIdIsNull("TENANT_ADMIN").orElse(null);
        if (tenantAdminRole == null) {
            log.error("TENANT_ADMIN role not found — tenant {} created with no admin membership", tenant.getId());
        } else {
            UserRole membership = UserRole.builder()
                    .userId(ownerId)
                    .role(tenantAdminRole)
                    .tenantId(tenant.getId())
                    .assignedBy(createdByUserId)
                    .build();
            userRoleRepository.save(membership);
            log.info("Owner assigned TENANT_ADMIN: userId={} tenantId={} assignedBy={}",
                    ownerId, tenant.getId(), createdByUserId);
        }

        recordTenantAudit(tenant.getId(), createdByUserId, "tenant_created", null, tenantAuditSnapshot(tenant));

        return toResponse(tenant);
    }

    private User resolveExistingOwner(CreateTenantRequest request) {
        if (request.getOwnerUserId() != null) {
            return userRepository.findByIdAndDeletedAtIsNull(request.getOwnerUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("Owner user not found: " + request.getOwnerUserId()));
        }
        String email = request.getOwnerEmail().trim().toLowerCase();
        return userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No existing account found for owner email '" + email + "' — the owner must already be registered"));
    }

    /**
     * Owner-only by default. Whoever provisioned the tenant (see {@link #createTenant}) can
     * set its initial basic info and assign the owner/plan, but does not retain edit rights
     * afterward: once a tenant has an assigned owner, only that owner manages its profile
     * going forward — unless the caller is a Platform Admin, added 2026-08-14
     * (docs/reviews/backend/rbac-role-permission-audit-2026-08-13.md mục 6) for support cases
     * where the owner has lost account access entirely (forgot password + lost recovery
     * email, account disabled, ...) and nobody else could otherwise fix the tenant's profile.
     * Matches how every other sensitive tenant resource already treats Platform Admin (IP
     * whitelist, tenant settings). Platform-level administrative actions
     * (suspend/reactivate/cancel/subscription changes) are separate endpoints, unaffected by
     * this restriction.
     */
    @Transactional
    public TenantResponse updateTenant(UUID tenantId, UpdateTenantRequest request, UUID userId, boolean callerIsPlatformAdmin) {
        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin && !userId.equals(tenant.getOwnerId())) {
            throw new AccessDeniedException("Only this tenant's owner may update its profile");
        }

        if (StringUtils.hasText(request.getDomain())
                && !request.getDomain().equals(tenant.getDomain())
                && tenantRepository.findByDomainAndDeletedAtIsNull(request.getDomain()).isPresent()) {
            throw new DuplicateResourceException("Domain '" + request.getDomain() + "' is already registered");
        }

        Map<String, Object> before = tenantAuditSnapshot(tenant);

        if (StringUtils.hasText(request.getName()))        tenant.setName(request.getName());
        if (request.getDomain() != null)                   tenant.setDomain(request.getDomain().isBlank() ? null : request.getDomain());
        if (request.getLogoUrl() != null)                  tenant.setLogoUrl(request.getLogoUrl().isBlank() ? null : request.getLogoUrl());
        if (StringUtils.hasText(request.getIndustry()))    tenant.setIndustry(request.getIndustry());
        if (StringUtils.hasText(request.getCountryCode())) tenant.setCountryCode(request.getCountryCode());
        if (StringUtils.hasText(request.getTimezone()))    tenant.setTimezone(request.getTimezone());
        if (StringUtils.hasText(request.getLocale()))      tenant.setLocale(request.getLocale());
        if (StringUtils.hasText(request.getCurrencyCode())) tenant.setCurrencyCode(request.getCurrencyCode());

        tenantRepository.save(tenant);
        log.info("Tenant updated: id={} by userId={}", tenantId, userId);
        recordTenantAudit(tenantId, userId, "tenant_updated", before, tenantAuditSnapshot(tenant));

        return toResponse(tenant);
    }

    /**
     * Ownership was previously permanent — set once at {@link #createTenant} with no way to
     * ever change it, meaning a company with no working access to its owner's account (left
     * the company, lost credentials, ...) had no self-service recovery path for
     * owner-gated actions (profile/settings/IP-whitelist/billing detail — see
     * docs/reviews/backend/rbac-role-permission-audit-2026-08-13.md mục 8). Added 2026-08-14.
     *
     * Callable by the CURRENT owner (self-service handoff) or a Platform Admin (support case,
     * same exemption pattern as {@link #updateTenant}). The new owner must already be an
     * active member of this tenant (holds some role here) — this is a handoff between people
     * who already work at the company, not a way to hand a stranger control of it. The new
     * owner is granted TENANT_ADMIN if they don't already hold it, so ownership and admin
     * access stay coherent; the OLD owner's own role membership is left untouched (they may
     * still work there).
     */
    @Transactional
    public TenantResponse transferOwner(UUID tenantId, TransferOwnerRequest request, UUID callerUserId, boolean callerIsPlatformAdmin) {
        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin && !callerUserId.equals(tenant.getOwnerId())) {
            throw new AccessDeniedException("Only this tenant's current owner may transfer ownership");
        }

        if (request.getNewOwnerUserId() == null && !StringUtils.hasText(request.getNewOwnerEmail())) {
            throw new IllegalArgumentException("newOwnerUserId or newOwnerEmail is required");
        }

        User newOwner = request.getNewOwnerUserId() != null
                ? userRepository.findByIdAndDeletedAtIsNull(request.getNewOwnerUserId())
                        .orElseThrow(() -> new ResourceNotFoundException("User not found: " + request.getNewOwnerUserId()))
                : userRepository.findByEmailAndDeletedAtIsNull(request.getNewOwnerEmail().trim().toLowerCase())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "No existing account found for email '" + request.getNewOwnerEmail() + "'"));

        if (newOwner.getId().equals(tenant.getOwnerId())) {
            throw new IllegalArgumentException("This user is already the owner of this tenant");
        }

        if (!userRoleRepository.existsByUserIdAndTenantIdAndDeletedAtIsNull(newOwner.getId(), tenantId)) {
            throw new IllegalArgumentException(
                    "The new owner must already be an active member of this tenant (hold some role here) — "
                    + "assign them a role first if they are new to the company.");
        }

        Map<String, Object> before = tenantAuditSnapshot(tenant);
        UUID previousOwnerId = tenant.getOwnerId();
        tenant.setOwnerId(newOwner.getId());
        tenantRepository.save(tenant);

        Role tenantAdminRole = roleRepository.findByNameAndTenantIdIsNull("TENANT_ADMIN").orElse(null);
        if (tenantAdminRole != null) {
            boolean alreadyHasActiveTenantAdmin = userRoleRepository
                    .findByUserIdAndRoleIdAndTenantId(newOwner.getId(), tenantAdminRole.getId(), tenantId)
                    .stream().anyMatch(ur -> ur.getDeletedAt() == null);
            if (!alreadyHasActiveTenantAdmin) {
                userRoleRepository.save(UserRole.builder()
                        .userId(newOwner.getId())
                        .role(tenantAdminRole)
                        .tenantId(tenantId)
                        .assignedBy(callerUserId)
                        .build());
                log.info("Granted TENANT_ADMIN to new owner: userId={} tenantId={}", newOwner.getId(), tenantId);
            }
        }

        log.info("Tenant ownership transferred: tenantId={} from={} to={} by={}",
                tenantId, previousOwnerId, newOwner.getId(), callerUserId);
        Map<String, Object> after = tenantAuditSnapshot(tenant);
        recordTenantAudit(tenantId, callerUserId, "tenant_owner_transferred", before, after);

        return toResponse(tenant);
    }

    private static final Set<String> SORTABLE_FIELDS = Set.of("name", "slug", "status", "createdAt", "updatedAt");

    @Transactional(readOnly = true)
    public PageResponse<TenantResponse> listTenants(
            String search, String status, String industry, String countryCode,
            String sortBy, String sortDir, int page, int size) {

        String resolvedSortBy = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, resolvedSortBy));

        Specification<Tenant> spec = TenantSpecification.build(search, status, industry, countryCode);
        Page<Tenant> tenantPage = tenantRepository.findAll(spec, pageable);

        List<UUID> ownerIds = tenantPage.getContent().stream()
                .map(Tenant::getOwnerId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());

        Map<UUID, User> userMap = userRepository.findAllById(ownerIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        Page<TenantResponse> resultPage = tenantPage.map(t -> toResponse(t, userMap.get(t.getOwnerId())));

        return PageResponse.from(resultPage);
    }

    @Transactional
    public TenantResponse cancelTenant(UUID tenantId, UUID callerUserId) {
        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if ("cancelled".equals(tenant.getStatus())) {
            throw new IllegalStateException("Tenant is already cancelled");
        }

        String previousStatus = tenant.getStatus();
        tenant.setStatus("cancelled");
        tenant.setPreSuspensionStatus(null);
        tenantRepository.save(tenant);
        redis.opsForValue().set(TENANT_SUSPENDED_PREFIX + tenantId, "1");
        log.info("Tenant cancelled: id={}", tenantId);
        recordTenantAudit(tenantId, callerUserId, "tenant_cancelled",
                Map.of("status", previousStatus), Map.of("status", "cancelled"));
        return toResponse(tenant);
    }

    @Transactional
    public TenantResponse suspendTenant(UUID tenantId, UUID callerUserId) {
        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if ("suspended".equals(tenant.getStatus())) {
            throw new IllegalStateException("Tenant is already suspended");
        }
        if ("cancelled".equals(tenant.getStatus())) {
            throw new IllegalStateException("Cannot suspend a cancelled tenant");
        }

        String previousStatus = tenant.getStatus();
        tenant.setPreSuspensionStatus(previousStatus);
        tenant.setStatus("suspended");
        tenantRepository.save(tenant);
        redis.opsForValue().set(TENANT_SUSPENDED_PREFIX + tenantId, "1");
        log.info("Tenant suspended: id={} previousStatus={}", tenantId, tenant.getPreSuspensionStatus());
        recordTenantAudit(tenantId, callerUserId, "tenant_suspended",
                Map.of("status", previousStatus), Map.of("status", "suspended"));
        return toResponse(tenant);
    }

    @Transactional
    public TenantResponse reactivateTenant(UUID tenantId, UUID callerUserId) {
        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!"suspended".equals(tenant.getStatus())) {
            throw new IllegalStateException("Tenant is not currently suspended");
        }

        String restoreStatus = tenant.getPreSuspensionStatus() != null
                ? tenant.getPreSuspensionStatus() : "active";
        tenant.setStatus(restoreStatus);
        tenant.setPreSuspensionStatus(null);
        tenantRepository.save(tenant);
        redis.delete(TENANT_SUSPENDED_PREFIX + tenantId);
        log.info("Tenant reactivated: id={} restoredStatus={}", tenantId, restoreStatus);
        recordTenantAudit(tenantId, callerUserId, "tenant_reactivated",
                Map.of("status", "suspended"), Map.of("status", restoreStatus));
        return toResponse(tenant);
    }

    private TenantResponse toResponse(Tenant tenant) {
        return toResponse(tenant, null);
    }

    private TenantResponse toResponse(Tenant tenant, User owner) {
        return TenantResponse.builder()
                .id(tenant.getId())
                .name(tenant.getName())
                .slug(tenant.getSlug())
                .domain(tenant.getDomain())
                .logoUrl(tenant.getLogoUrl())
                .industry(tenant.getIndustry())
                .countryCode(tenant.getCountryCode())
                .timezone(tenant.getTimezone())
                .locale(tenant.getLocale())
                .currencyCode(tenant.getCurrencyCode())
                .status(tenant.getStatus())
                .ownerId(tenant.getOwnerId())
                .ownerName(owner != null ? owner.getDisplayName() : null)
                .ownerEmail(owner != null ? owner.getEmail() : null)
                .createdAt(tenant.getCreatedAt())
                .updatedAt(tenant.getUpdatedAt())
                .build();
    }
}
