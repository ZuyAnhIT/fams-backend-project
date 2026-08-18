package com.fams.modules.randomcheck.service;

import com.fams.modules.randomcheck.dto.request.CreateRandomCheckConfigRequest;
import com.fams.modules.randomcheck.dto.request.UpdateApplicableRolesRequest;
import com.fams.modules.randomcheck.dto.request.UpdateCheckModeRequest;
import com.fams.modules.randomcheck.dto.request.UpdateRandomCheckConfigRequest;
import com.fams.modules.randomcheck.dto.response.RandomCheckConfigResponse;
import com.fams.modules.randomcheck.entity.RandomCheckConfig;
import com.fams.modules.randomcheck.repository.RandomCheckConfigRepository;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RandomCheckConfigService {

    private static final String PERM_CONFIGURE = "randomchecks:configure";

    private final RandomCheckConfigRepository configRepository;
    private final UserRoleRepository userRoleRepository;
    private final SiteRepository siteRepository;
    private final SiteScopeService siteScopeService;
    private final AuditLogService auditLogService;

    public RandomCheckConfigService(RandomCheckConfigRepository configRepository,
                                    UserRoleRepository userRoleRepository,
                                    SiteRepository siteRepository,
                                    SiteScopeService siteScopeService,
                                    AuditLogService auditLogService) {
        this.configRepository = configRepository;
        this.userRoleRepository = userRoleRepository;
        this.siteRepository = siteRepository;
        this.siteScopeService = siteScopeService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public RandomCheckConfigResponse createTenantDefault(UUID tenantId,
                                                         CreateRandomCheckConfigRequest req,
                                                         UUID callerId,
                                                         boolean callerIsPlatformAdmin) {
        checkPermission(callerId, tenantId, callerIsPlatformAdmin);
        validateSchedulingFields(req.getAllowedStartTime(), req.getAllowedEndTime(),
                req.getChecksPerShift(), req.getMinIntervalMinutes());

        if (configRepository.existsByTenantIdAndSiteIdIsNullAndDeletedAtIsNull(tenantId)) {
            throw new DuplicateResourceException(
                    "A default random check configuration already exists for this tenant. Use PUT to update it.");
        }

        RandomCheckConfig config = RandomCheckConfig.builder()
                .tenantId(tenantId)
                .siteId(null)
                .checksPerShift(req.getChecksPerShift())
                .minIntervalMinutes(req.getMinIntervalMinutes())
                .allowedStartTime(req.getAllowedStartTime())
                .allowedEndTime(req.getAllowedEndTime())
                .checkMode(req.getCheckMode())
                .applicableRoles(toRolesString(req.getApplicableRoles()))
                .responseWindowSeconds(req.getResponseWindowSeconds())
                .failureEscalationThreshold(
                        req.getFailureEscalationThreshold() != null ? req.getFailureEscalationThreshold() : 0)
                .isActive(true)
                .createdBy(callerId)
                .build();

        config = configRepository.save(config);
        log.info("Created tenant-default random check config id={} tenantId={} createdBy={}", config.getId(), tenantId, callerId);
        auditLogService.record(
                tenantId, callerId, null,
                "RandomCheckConfig", config.getId().toString(), "random_check_config_created",
                null, configSnapshot(config),
                HttpRequestUtils.currentRequestId(), null, null);
        return toResponse(config);
    }

    @Transactional
    public RandomCheckConfigResponse createSiteOverride(UUID tenantId, UUID siteId,
                                                        CreateRandomCheckConfigRequest req,
                                                        UUID callerId, boolean callerIsPlatformAdmin) {
        checkPermission(callerId, tenantId, callerIsPlatformAdmin);
        assertSiteInScope(callerId, tenantId, siteId, callerIsPlatformAdmin);
        validateSchedulingFields(req.getAllowedStartTime(), req.getAllowedEndTime(),
                req.getChecksPerShift(), req.getMinIntervalMinutes());

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        if (configRepository.findBySite(tenantId, siteId).isPresent()) {
            throw new DuplicateResourceException(
                    "A random check configuration for this site already exists. Use PUT to update it.");
        }

        RandomCheckConfig config = RandomCheckConfig.builder()
                .tenantId(tenantId)
                .siteId(siteId)
                .checksPerShift(req.getChecksPerShift())
                .minIntervalMinutes(req.getMinIntervalMinutes())
                .allowedStartTime(req.getAllowedStartTime())
                .allowedEndTime(req.getAllowedEndTime())
                .checkMode(req.getCheckMode())
                .applicableRoles(toRolesString(req.getApplicableRoles()))
                .responseWindowSeconds(req.getResponseWindowSeconds())
                .failureEscalationThreshold(
                        req.getFailureEscalationThreshold() != null ? req.getFailureEscalationThreshold() : 0)
                .isActive(true)
                .createdBy(callerId)
                .build();

        config = configRepository.save(config);
        log.info("Created site random check config id={} tenantId={} siteId={} createdBy={}",
                config.getId(), tenantId, siteId, callerId);
        auditLogService.record(
                tenantId, callerId, null,
                "RandomCheckConfig", config.getId().toString(), "random_check_config_created",
                null, configSnapshot(config),
                HttpRequestUtils.currentRequestId(), null, null);
        return toResponse(config);
    }

    @Transactional(readOnly = true)
    public RandomCheckConfigResponse getSiteOverride(UUID tenantId, UUID siteId,
                                                     UUID callerId, boolean callerIsPlatformAdmin) {
        checkPermission(callerId, tenantId, callerIsPlatformAdmin);
        assertSiteInScope(callerId, tenantId, siteId, callerIsPlatformAdmin);

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        return configRepository.findBySite(tenantId, siteId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No random check configuration found for site: " + siteId));
    }

    /**
     * Returns whichever config would actually be applied to this site right now — site override
     * if one exists (and is active), else the tenant default — the exact same resolution order
     * used by ScheduledCheckGeneratorService/ManualCheckService at dispatch time. Added because
     * previously a caller had to GET the site override (handling its 404), then separately GET
     * the tenant default, and replicate this fallback client-side to answer "what config applies
     * here" — now a third place to keep in sync with the two dispatch services that already
     * implement this same lookup.
     */
    @Transactional(readOnly = true)
    public RandomCheckConfigResponse getEffectiveConfig(UUID tenantId, UUID siteId,
                                                        UUID callerId, boolean callerIsPlatformAdmin) {
        checkPermission(callerId, tenantId, callerIsPlatformAdmin);
        assertSiteInScope(callerId, tenantId, siteId, callerIsPlatformAdmin);

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        Optional<RandomCheckConfig> override = configRepository.findBySite(tenantId, siteId);
        if (override.isPresent()) {
            return toResponse(override.get(), "site_override");
        }

        RandomCheckConfig tenantDefault = configRepository.findTenantDefault(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No random check configuration applies to this site — no site override "
                                + "and no tenant default configured"));
        return toResponse(tenantDefault, "tenant_default");
    }

    @Transactional(readOnly = true)
    public RandomCheckConfigResponse getTenantDefault(UUID tenantId, UUID callerId, boolean callerIsPlatformAdmin) {
        checkPermission(callerId, tenantId, callerIsPlatformAdmin);
        RandomCheckConfig config = configRepository.findTenantDefault(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("No default random check configuration found for this tenant"));
        return toResponse(config);
    }

    @Transactional(readOnly = true)
    public List<RandomCheckConfigResponse> listConfigs(UUID tenantId, UUID callerId, boolean callerIsPlatformAdmin) {
        checkPermission(callerId, tenantId, callerIsPlatformAdmin);
        java.util.Optional<Set<UUID>> allowedSiteIds =
                siteScopeService.resolveAllowedSiteIds(callerId, tenantId, callerIsPlatformAdmin);
        return configRepository.findAllByTenant(tenantId).stream()
                // Tenant-wide default configs (siteId null) always stay visible — they're not
                // site-specific data. Site overrides are filtered to the caller's allowed sites.
                .filter(c -> c.getSiteId() == null
                        || allowedSiteIds.isEmpty()
                        || allowedSiteIds.get().contains(c.getSiteId()))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RandomCheckConfigResponse getConfig(UUID tenantId, UUID configId, UUID callerId, boolean callerIsPlatformAdmin) {
        checkPermission(callerId, tenantId, callerIsPlatformAdmin);
        RandomCheckConfig config = configRepository.findByIdAndTenant(configId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Random check config not found: " + configId));
        assertConfigInScope(callerId, tenantId, config, callerIsPlatformAdmin);
        return toResponse(config);
    }

    @Transactional
    public RandomCheckConfigResponse updateConfig(UUID tenantId, UUID configId,
                                                   UpdateRandomCheckConfigRequest req,
                                                   UUID callerId, boolean callerIsPlatformAdmin) {
        checkPermission(callerId, tenantId, callerIsPlatformAdmin);

        RandomCheckConfig config = configRepository.findByIdAndTenant(configId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Random check config not found: " + configId));
        assertConfigInScope(callerId, tenantId, config, callerIsPlatformAdmin);

        LocalTime effectiveStart = req.getAllowedStartTime() != null ? req.getAllowedStartTime() : config.getAllowedStartTime();
        LocalTime effectiveEnd   = req.getAllowedEndTime()   != null ? req.getAllowedEndTime()   : config.getAllowedEndTime();
        int effectiveChecks   = req.getChecksPerShift()      != null ? req.getChecksPerShift()      : config.getChecksPerShift();
        int effectiveInterval = req.getMinIntervalMinutes()  != null ? req.getMinIntervalMinutes()  : config.getMinIntervalMinutes();
        validateSchedulingFields(effectiveStart, effectiveEnd, effectiveChecks, effectiveInterval);

        Map<String, Object> oldValue = configSnapshot(config);

        if (req.getChecksPerShift() != null) config.setChecksPerShift(req.getChecksPerShift());
        if (req.getMinIntervalMinutes() != null) config.setMinIntervalMinutes(req.getMinIntervalMinutes());
        if (req.getAllowedStartTime() != null) config.setAllowedStartTime(req.getAllowedStartTime());
        if (req.getAllowedEndTime() != null) config.setAllowedEndTime(req.getAllowedEndTime());
        if (req.getCheckMode() != null) config.setCheckMode(req.getCheckMode());
        if (req.getApplicableRoles() != null) config.setApplicableRoles(toRolesString(req.getApplicableRoles()));
        if (req.getResponseWindowSeconds() != null) config.setResponseWindowSeconds(req.getResponseWindowSeconds());
        if (req.getIsActive() != null) config.setActive(req.getIsActive());
        if (req.getFailureEscalationThreshold() != null) config.setFailureEscalationThreshold(req.getFailureEscalationThreshold());

        config = configRepository.save(config);
        log.info("Updated random check config id={} tenantId={} updatedBy={}", configId, tenantId, callerId);
        auditLogService.record(
                tenantId, callerId, null,
                "RandomCheckConfig", configId.toString(), "random_check_config_updated",
                oldValue, configSnapshot(config),
                HttpRequestUtils.currentRequestId(), null, null);
        return toResponse(config);
    }

    @Transactional
    public RandomCheckConfigResponse updateApplicableRoles(UUID tenantId, UUID configId,
                                                           UpdateApplicableRolesRequest req,
                                                           UUID callerId, boolean callerIsPlatformAdmin) {
        checkPermission(callerId, tenantId, callerIsPlatformAdmin);

        RandomCheckConfig config = configRepository.findByIdAndTenant(configId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Random check config not found: " + configId));
        assertConfigInScope(callerId, tenantId, config, callerIsPlatformAdmin);

        String oldRoles = config.getApplicableRoles();
        config.setApplicableRoles(toRolesString(req.getApplicableRoles()));
        config = configRepository.save(config);
        log.info("Updated applicable_roles for config id={} tenantId={} roles='{}' updatedBy={}",
                configId, tenantId, config.getApplicableRoles(), callerId);
        auditLogService.record(
                tenantId, callerId, null,
                "RandomCheckConfig", configId.toString(), "random_check_config_applicable_roles_updated",
                Map.of("applicableRoles", oldRoles == null ? "" : oldRoles),
                Map.of("applicableRoles", config.getApplicableRoles()),
                HttpRequestUtils.currentRequestId(), null, null);
        return toResponse(config);
    }

    @Transactional
    public RandomCheckConfigResponse updateCheckMode(UUID tenantId, UUID configId,
                                                     UpdateCheckModeRequest req,
                                                     UUID callerId, boolean callerIsPlatformAdmin) {
        checkPermission(callerId, tenantId, callerIsPlatformAdmin);

        RandomCheckConfig config = configRepository.findByIdAndTenant(configId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Random check config not found: " + configId));
        assertConfigInScope(callerId, tenantId, config, callerIsPlatformAdmin);

        String oldMode = config.getCheckMode();
        config.setCheckMode(req.getCheckMode());
        config = configRepository.save(config);
        log.info("Updated check_mode to '{}' for config id={} tenantId={} updatedBy={}",
                req.getCheckMode(), configId, tenantId, callerId);
        auditLogService.record(
                tenantId, callerId, null,
                "RandomCheckConfig", configId.toString(), "random_check_config_check_mode_updated",
                Map.of("checkMode", oldMode), Map.of("checkMode", config.getCheckMode()),
                HttpRequestUtils.currentRequestId(), null, null);
        return toResponse(config);
    }

    @Transactional
    public void deleteConfig(UUID tenantId, UUID configId, UUID callerId, boolean callerIsPlatformAdmin) {
        checkPermission(callerId, tenantId, callerIsPlatformAdmin);

        RandomCheckConfig config = configRepository.findByIdAndTenant(configId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Random check config not found: " + configId));
        assertConfigInScope(callerId, tenantId, config, callerIsPlatformAdmin);

        config.setDeletedAt(OffsetDateTime.now());
        configRepository.save(config);
        log.info("Soft-deleted random check config id={} tenantId={} deletedBy={}", configId, tenantId, callerId);
        auditLogService.record(
                tenantId, callerId, null,
                "RandomCheckConfig", configId.toString(), "random_check_config_deleted",
                configSnapshot(config), null,
                HttpRequestUtils.currentRequestId(), null, null);
    }

    private void validateSchedulingFields(LocalTime startTime, LocalTime endTime,
                                          int checksPerShift, int minIntervalMinutes) {
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException(
                    "allowed_end_time must be after allowed_start_time");
        }
        long windowMinutes = java.time.Duration.between(startTime, endTime).toMinutes();
        // checks_per_shift checks need (checks_per_shift - 1) intervals between them
        long minWindowNeeded = (long) (checksPerShift - 1) * minIntervalMinutes;
        if (minIntervalMinutes > 0 && checksPerShift > 1 && windowMinutes < minWindowNeeded) {
            throw new IllegalArgumentException(String.format(
                    "Time window (%d min) is too short: %d checks with %d min intervals require at least %d min",
                    windowMinutes, checksPerShift, minIntervalMinutes, minWindowNeeded));
        }
    }

    private void checkPermission(UUID callerId, UUID tenantId, boolean callerIsPlatformAdmin) {
        if (callerIsPlatformAdmin) return;
        Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerId, tenantId);
        if (!perms.contains(PERM_CONFIGURE)) {
            throw new AccessDeniedException("Missing permission: " + PERM_CONFIGURE);
        }
    }

    private void assertSiteInScope(UUID callerId, UUID tenantId, UUID siteId, boolean callerIsPlatformAdmin) {
        if (!siteScopeService.isSiteAllowed(callerId, tenantId, siteId, callerIsPlatformAdmin)) {
            throw new AccessDeniedException("You do not have permission to configure random checks for this site");
        }
    }

    /** Tenant-wide default configs (siteId null) aren't site-restricted access — only a
     *  per-site override config needs the caller's site scope checked. */
    private void assertConfigInScope(UUID callerId, UUID tenantId, RandomCheckConfig config, boolean callerIsPlatformAdmin) {
        if (config.getSiteId() != null) {
            assertSiteInScope(callerId, tenantId, config.getSiteId(), callerIsPlatformAdmin);
        }
    }

    /** Snapshot of every mutable field, for audit log old/new values. */
    private Map<String, Object> configSnapshot(RandomCheckConfig c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("checksPerShift", c.getChecksPerShift());
        map.put("minIntervalMinutes", c.getMinIntervalMinutes());
        map.put("allowedStartTime", String.valueOf(c.getAllowedStartTime()));
        map.put("allowedEndTime", String.valueOf(c.getAllowedEndTime()));
        map.put("checkMode", c.getCheckMode());
        map.put("applicableRoles", c.getApplicableRoles());
        map.put("responseWindowSeconds", c.getResponseWindowSeconds());
        map.put("failureEscalationThreshold", c.getFailureEscalationThreshold());
        map.put("isActive", c.isActive());
        return map;
    }

    private String toRolesString(List<String> roles) {
        if (roles == null || roles.isEmpty()) return "";
        return String.join(",", roles);
    }

    private RandomCheckConfigResponse toResponse(RandomCheckConfig c) {
        return toResponse(c, null);
    }

    private RandomCheckConfigResponse toResponse(RandomCheckConfig c, String resolvedFrom) {
        List<String> roles = (c.getApplicableRoles() == null || c.getApplicableRoles().isBlank())
                ? List.of()
                : Arrays.asList(c.getApplicableRoles().split(","));
        return RandomCheckConfigResponse.builder()
                .id(c.getId())
                .tenantId(c.getTenantId())
                .siteId(c.getSiteId())
                .checksPerShift(c.getChecksPerShift())
                .minIntervalMinutes(c.getMinIntervalMinutes())
                .allowedStartTime(c.getAllowedStartTime())
                .allowedEndTime(c.getAllowedEndTime())
                .checkMode(c.getCheckMode())
                .applicableRoles(roles)
                .responseWindowSeconds(c.getResponseWindowSeconds())
                .failureEscalationThreshold(c.getFailureEscalationThreshold())
                .isActive(c.isActive())
                .createdBy(c.getCreatedBy())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .resolvedFrom(resolvedFrom)
                .build();
    }
}
