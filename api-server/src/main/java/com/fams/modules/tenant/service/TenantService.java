package com.fams.modules.tenant.service;

import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.subscription.entity.TenantSubscription;
import com.fams.modules.subscription.entity.TenantSubscription.BillingCycle;
import com.fams.modules.subscription.entity.TenantSubscription.SubscriptionStatus;
import com.fams.modules.subscription.repository.PlanRepository;
import com.fams.modules.subscription.repository.TenantSubscriptionRepository;
import com.fams.modules.tenant.dto.request.CreateTenantRequest;
import com.fams.modules.tenant.dto.request.UpdateTenantRequest;
import com.fams.modules.tenant.dto.response.TenantResponse;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.tenant.specification.TenantSpecification;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
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
    private final StringRedisTemplate redis;

    public TenantService(TenantRepository tenantRepository, UserRepository userRepository,
                         PlanRepository planRepository,
                         TenantSubscriptionRepository subscriptionRepository,
                         StringRedisTemplate redis) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.redis = redis;
    }

    @Transactional
    public TenantResponse createTenant(CreateTenantRequest request, UUID createdByUserId) {
        if (tenantRepository.findBySlugAndDeletedAtIsNull(request.getSlug()).isPresent()) {
            throw new DuplicateResourceException("Slug '" + request.getSlug() + "' is already taken");
        }

        if (StringUtils.hasText(request.getDomain())
                && tenantRepository.findByDomainAndDeletedAtIsNull(request.getDomain()).isPresent()) {
            throw new DuplicateResourceException("Domain '" + request.getDomain() + "' is already registered");
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
                .status("trial")
                .ownerId(createdByUserId)
                .build();

        tenantRepository.save(tenant);
        log.info("Tenant created: id={} slug={} by userId={}", tenant.getId(), tenant.getSlug(), createdByUserId);

        planRepository.findByIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc().stream()
                .findFirst()
                .ifPresentOrElse(
                        defaultPlan -> {
                            TenantSubscription sub = TenantSubscription.builder()
                                    .tenantId(tenant.getId())
                                    .planId(defaultPlan.getId())
                                    .status(SubscriptionStatus.TRIAL)
                                    .billingCycle(BillingCycle.MONTHLY)
                                    .build();
                            subscriptionRepository.save(sub);
                            log.info("Default trial subscription created: tenantId={} planId={}",
                                    tenant.getId(), defaultPlan.getId());
                        },
                        () -> log.warn("No active plan found — tenant {} created without a subscription", tenant.getId())
                );

        return toResponse(tenant);
    }

    @Transactional
    public TenantResponse updateTenant(UUID tenantId, UpdateTenantRequest request, UUID userId, boolean isPlatformAdmin) {
        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!isPlatformAdmin && !userId.equals(tenant.getOwnerId())) {
            throw new AccessDeniedException("You do not have permission to update this tenant");
        }

        if (StringUtils.hasText(request.getDomain())
                && !request.getDomain().equals(tenant.getDomain())
                && tenantRepository.findByDomainAndDeletedAtIsNull(request.getDomain()).isPresent()) {
            throw new DuplicateResourceException("Domain '" + request.getDomain() + "' is already registered");
        }

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
    public TenantResponse cancelTenant(UUID tenantId) {
        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if ("cancelled".equals(tenant.getStatus())) {
            throw new IllegalStateException("Tenant is already cancelled");
        }

        tenant.setStatus("cancelled");
        tenant.setPreSuspensionStatus(null);
        tenantRepository.save(tenant);
        redis.opsForValue().set(TENANT_SUSPENDED_PREFIX + tenantId, "1");
        log.info("Tenant cancelled: id={}", tenantId);
        return toResponse(tenant);
    }

    @Transactional
    public TenantResponse suspendTenant(UUID tenantId) {
        Tenant tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if ("suspended".equals(tenant.getStatus())) {
            throw new IllegalStateException("Tenant is already suspended");
        }
        if ("cancelled".equals(tenant.getStatus())) {
            throw new IllegalStateException("Cannot suspend a cancelled tenant");
        }

        tenant.setPreSuspensionStatus(tenant.getStatus());
        tenant.setStatus("suspended");
        tenantRepository.save(tenant);
        redis.opsForValue().set(TENANT_SUSPENDED_PREFIX + tenantId, "1");
        log.info("Tenant suspended: id={} previousStatus={}", tenantId, tenant.getPreSuspensionStatus());
        return toResponse(tenant);
    }

    @Transactional
    public TenantResponse reactivateTenant(UUID tenantId) {
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
