package com.fams.modules.tenant.service;

import com.fams.modules.tenant.dto.request.CreateTenantRequest;
import com.fams.modules.tenant.dto.request.UpdateTenantRequest;
import com.fams.modules.tenant.dto.response.TenantResponse;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.tenant.specification.TenantSpecification;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
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

import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class TenantService {

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
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
        Page<TenantResponse> resultPage = tenantRepository.findAll(spec, pageable).map(this::toResponse);

        return PageResponse.from(resultPage);
    }

    private TenantResponse toResponse(Tenant tenant) {
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
                .createdAt(tenant.getCreatedAt())
                .updatedAt(tenant.getUpdatedAt())
                .build();
    }
}
