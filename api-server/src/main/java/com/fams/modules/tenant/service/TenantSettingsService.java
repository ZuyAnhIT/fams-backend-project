package com.fams.modules.tenant.service;

import com.fams.modules.tenant.dto.request.UpdateTenantSettingsRequest;
import com.fams.modules.tenant.dto.response.TenantSettingsResponse;
import com.fams.modules.tenant.entity.TenantSettings;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.tenant.repository.TenantSettingsRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Slf4j
@Service
public class TenantSettingsService {

    private final TenantRepository tenantRepository;
    private final TenantSettingsRepository settingsRepository;

    public TenantSettingsService(TenantRepository tenantRepository,
                                 TenantSettingsRepository settingsRepository) {
        this.tenantRepository = tenantRepository;
        this.settingsRepository = settingsRepository;
    }

    @Transactional
    public TenantSettingsResponse getSettings(UUID tenantId, UUID userId, boolean isPlatformAdmin) {
        assertTenantAccessible(tenantId, userId, isPlatformAdmin);

        TenantSettings settings = settingsRepository.findByTenantId(tenantId)
                .orElseGet(() -> createDefaults(tenantId));

        return toResponse(settings);
    }

    @Transactional
    public TenantSettingsResponse updateSettings(UUID tenantId, UpdateTenantSettingsRequest request,
                                                 UUID userId, boolean isPlatformAdmin) {
        assertTenantAccessible(tenantId, userId, isPlatformAdmin);

        TenantSettings settings = settingsRepository.findByTenantId(tenantId)
                .orElseGet(() -> createDefaults(tenantId));

        if (StringUtils.hasText(request.getDateFormat()))      settings.setDateFormat(request.getDateFormat());
        if (StringUtils.hasText(request.getTimeFormat()))      settings.setTimeFormat(request.getTimeFormat());
        if (request.getBrandPrimaryColor() != null)            settings.setBrandPrimaryColor(nullIfBlank(request.getBrandPrimaryColor()));
        if (request.getBrandSecondaryColor() != null)          settings.setBrandSecondaryColor(nullIfBlank(request.getBrandSecondaryColor()));
        if (request.getBrandAccentColor() != null)             settings.setBrandAccentColor(nullIfBlank(request.getBrandAccentColor()));
        if (request.getEmployeeCodePrefix() != null)
            settings.setEmployeeCodePrefix(request.getEmployeeCodePrefix().isBlank() ? null : request.getEmployeeCodePrefix().trim().toUpperCase());
        if (request.getEmployeeCodePadding() != null)
            settings.setEmployeeCodePadding(request.getEmployeeCodePadding().shortValue());

        settingsRepository.save(settings);
        log.info("Tenant settings updated: tenantId={} by userId={}", tenantId, userId);

        return toResponse(settings);
    }

    private void assertTenantAccessible(UUID tenantId, UUID userId, boolean isPlatformAdmin) {
        var tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!isPlatformAdmin && !userId.equals(tenant.getOwnerId())) {
            throw new AccessDeniedException("You do not have permission to access this tenant's settings");
        }
    }

    private TenantSettings createDefaults(UUID tenantId) {
        TenantSettings defaults = TenantSettings.builder()
                .tenantId(tenantId)
                .dateFormat("DD/MM/YYYY")
                .timeFormat("HH:mm")
                .build();
        return settingsRepository.save(defaults);
    }

    /**
     * Atomically increments the employee code sequence and returns the formatted code.
     * Returns null if no prefix is configured for this tenant.
     */
    @Transactional
    public String generateNextEmployeeCode(UUID tenantId) {
        TenantSettings settings = settingsRepository.findByTenantIdForUpdate(tenantId)
                .orElse(null);
        if (settings == null || !StringUtils.hasText(settings.getEmployeeCodePrefix())) {
            return null;
        }
        long next = settings.getEmployeeCodeSeq() + 1;
        settings.setEmployeeCodeSeq(next);
        settingsRepository.save(settings);

        String format = "%0" + settings.getEmployeeCodePadding() + "d";
        return settings.getEmployeeCodePrefix() + "-" + String.format(format, next);
    }

    private TenantSettingsResponse toResponse(TenantSettings s) {
        return TenantSettingsResponse.builder()
                .id(s.getId())
                .tenantId(s.getTenantId())
                .dateFormat(s.getDateFormat())
                .timeFormat(s.getTimeFormat())
                .brandPrimaryColor(s.getBrandPrimaryColor())
                .brandSecondaryColor(s.getBrandSecondaryColor())
                .brandAccentColor(s.getBrandAccentColor())
                .employeeCodePrefix(s.getEmployeeCodePrefix())
                .employeeCodePadding(s.getEmployeeCodePadding())
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    private static String nullIfBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
