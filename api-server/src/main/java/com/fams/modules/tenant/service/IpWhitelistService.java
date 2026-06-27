package com.fams.modules.tenant.service;

import com.fams.modules.tenant.dto.request.CreateIpWhitelistRequest;
import com.fams.modules.tenant.dto.request.UpdateIpWhitelistRequest;
import com.fams.modules.tenant.dto.response.IpWhitelistResponse;
import com.fams.modules.tenant.entity.TenantIpWhitelist;
import com.fams.modules.tenant.repository.TenantIpWhitelistRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
public class IpWhitelistService {

    private final TenantRepository tenantRepository;
    private final TenantIpWhitelistRepository whitelistRepository;

    public IpWhitelistService(TenantRepository tenantRepository,
                              TenantIpWhitelistRepository whitelistRepository) {
        this.tenantRepository = tenantRepository;
        this.whitelistRepository = whitelistRepository;
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

        TenantIpWhitelist entry = TenantIpWhitelist.builder()
                .tenantId(tenantId)
                .ipAddress(request.getIpAddress().trim())
                .label(request.getLabel())
                .scope(StringUtils.hasText(request.getScope()) ? request.getScope() : "all")
                .build();

        whitelistRepository.save(entry);
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
        if (StringUtils.hasText(request.getScope()))    entry.setScope(request.getScope());
        if (request.getIsActive() != null) entry.setActive(request.getIsActive());

        whitelistRepository.save(entry);
        log.info("IP whitelist entry updated: id={} tenantId={} by userId={}", entryId, tenantId, userId);

        return toResponse(entry);
    }

    @Transactional
    public void deleteEntry(UUID tenantId, UUID entryId, UUID userId, boolean isPlatformAdmin) {
        assertAccess(tenantId, userId, isPlatformAdmin);

        TenantIpWhitelist entry = whitelistRepository.findByIdAndTenantIdAndDeletedAtIsNull(entryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("IP whitelist entry not found: " + entryId));

        entry.setDeletedAt(OffsetDateTime.now());
        whitelistRepository.save(entry);
        log.info("IP whitelist entry deleted: id={} tenantId={} by userId={}", entryId, tenantId, userId);
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
                .scope(e.getScope())
                .isActive(e.isActive())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
