package com.fams.modules.tenant.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.tenant.dto.request.UpdateTenantSettingsRequest;
import com.fams.modules.tenant.dto.response.TenantSettingsResponse;
import com.fams.modules.tenant.entity.TenantSettings;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.tenant.repository.TenantSettingsRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class TenantSettingsService {

    private final TenantRepository tenantRepository;
    private final TenantSettingsRepository settingsRepository;
    private final UserRoleRepository userRoleRepository;
    private final AuditLogService auditLogService;

    public TenantSettingsService(TenantRepository tenantRepository,
                                 TenantSettingsRepository settingsRepository,
                                 UserRoleRepository userRoleRepository,
                                 AuditLogService auditLogService) {
        this.tenantRepository = tenantRepository;
        this.settingsRepository = settingsRepository;
        this.userRoleRepository = userRoleRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public TenantSettingsResponse getSettings(UUID tenantId, UUID userId, boolean isPlatformAdmin) {
        assertTenantViewable(tenantId, userId, isPlatformAdmin);

        TenantSettings settings = settingsRepository.findByTenantId(tenantId)
                .orElseGet(() -> createDefaults(tenantId));

        return toResponse(settings);
    }

    /**
     * Owner-only, matching TenantService.updateTenant's product decision (24/07/2026):
     * display/branding settings are part of the same "company configuration" surface as the
     * tenant profile — Platform Admin/Staff can VIEW them (for support) but not change them,
     * even for a tenant they provisioned.
     */
    @Transactional
    public TenantSettingsResponse updateSettings(UUID tenantId, UpdateTenantSettingsRequest request,
                                                 UUID userId) {
        assertOwner(tenantId, userId);

        TenantSettings settings = settingsRepository.findByTenantId(tenantId)
                .orElseGet(() -> createDefaults(tenantId));

        Map<String, Object> before = settingsAuditSnapshot(settings);

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

        try {
            auditLogService.record(
                    tenantId, userId, null,
                    "TenantSettings", tenantId.toString(), "tenant_settings_updated",
                    before, settingsAuditSnapshot(settings),
                    HttpRequestUtils.currentRequestId(),
                    HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception ex) {
            log.warn("Failed to record tenant_settings_updated audit for tenantId={}: {}", tenantId, ex.getMessage());
        }

        return toResponse(settings);
    }

    private Map<String, Object> settingsAuditSnapshot(TenantSettings settings) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("dateFormat", settings.getDateFormat());
        snapshot.put("timeFormat", settings.getTimeFormat());
        snapshot.put("brandPrimaryColor", settings.getBrandPrimaryColor());
        snapshot.put("brandSecondaryColor", settings.getBrandSecondaryColor());
        snapshot.put("brandAccentColor", settings.getBrandAccentColor());
        snapshot.put("employeeCodePrefix", settings.getEmployeeCodePrefix());
        snapshot.put("employeeCodePadding", settings.getEmployeeCodePadding());
        return snapshot;
    }

    /**
     * Unlike the tenant profile (owner-or-admin only), display/format settings (date format,
     * currency-adjacent locale bits, brand colors) are read by every member's own client to
     * render the app consistently for that company — so GET is open to any active member of
     * the tenant, not just its owner. PATCH stays owner-only (see updateSettings).
     */
    private void assertTenantViewable(UUID tenantId, UUID userId, boolean isPlatformAdmin) {
        var tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (isPlatformAdmin || userId.equals(tenant.getOwnerId())) {
            return;
        }
        if (!userRoleRepository.existsByUserIdAndTenantIdAndDeletedAtIsNull(userId, tenantId)) {
            throw new AccessDeniedException("You do not have permission to access this tenant's settings");
        }
    }

    private void assertOwner(UUID tenantId, UUID userId) {
        var tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!userId.equals(tenant.getOwnerId())) {
            throw new AccessDeniedException("Only this tenant's owner may update its settings");
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
