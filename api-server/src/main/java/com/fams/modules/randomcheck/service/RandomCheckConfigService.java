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
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
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

    private static final LocalTime DEFAULT_ALLOWED_START = LocalTime.of(8, 0);
    private static final LocalTime DEFAULT_ALLOWED_END = LocalTime.of(17, 0);

    private static final String PERM_CONFIGURE = "randomchecks:configure";

    private final RandomCheckConfigRepository configRepository;
    private final UserRoleRepository userRoleRepository;
    private final SiteRepository siteRepository;
    private final ShiftRepository shiftRepository;
    private final SiteScopeService siteScopeService;
    private final AuditLogService auditLogService;

    public RandomCheckConfigService(RandomCheckConfigRepository configRepository,
                                    UserRoleRepository userRoleRepository,
                                    SiteRepository siteRepository,
                                    ShiftRepository shiftRepository,
                                    SiteScopeService siteScopeService,
                                    AuditLogService auditLogService) {
        this.configRepository = configRepository;
        this.userRoleRepository = userRoleRepository;
        this.siteRepository = siteRepository;
        this.shiftRepository = shiftRepository;
        this.siteScopeService = siteScopeService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public RandomCheckConfigResponse createTenantDefault(UUID tenantId,
                                                         CreateRandomCheckConfigRequest req,
                                                         UUID callerId,
                                                         boolean callerIsPlatformAdmin) {
        checkPermission(callerId, tenantId, callerIsPlatformAdmin);
        LocalTime storedStart = req.getAllowedStartTime() != null
                ? req.getAllowedStartTime() : DEFAULT_ALLOWED_START;
        LocalTime storedEnd = req.getAllowedEndTime() != null
                ? req.getAllowedEndTime() : DEFAULT_ALLOWED_END;
        validateSchedulingFields(req.getWindowMode(), req.getAllowedStartTime(), req.getAllowedEndTime(),
                req.getChecksPerShift(), req.getMinIntervalMinutes());
        validateShiftCoverage(tenantId, null, req.getWindowMode(), storedStart,
                storedEnd, req.getChecksPerShift(), req.getMinIntervalMinutes());

        if (configRepository.existsByTenantIdAndSiteIdIsNullAndDeletedAtIsNull(tenantId)) {
            throw new DuplicateResourceException(
                    "A default random check configuration already exists for this tenant. Use PUT to update it.");
        }

        RandomCheckConfig config = RandomCheckConfig.builder()
                .tenantId(tenantId)
                .siteId(null)
                .checksPerShift(req.getChecksPerShift())
                .minIntervalMinutes(req.getMinIntervalMinutes())
                .allowedStartTime(storedStart)
                .allowedEndTime(storedEnd)
                .windowMode(req.getWindowMode())
                .checkMode(req.getCheckMode())
                .applicableRoles(toRolesString(req.getApplicableRoles()))
                .responseWindowSeconds(req.getResponseWindowSeconds())
                .failureEscalationThreshold(
                        req.getFailureEscalationThreshold() != null ? req.getFailureEscalationThreshold() : 0)
                .isActive(true)
                .manualChecksAllowed(!Boolean.FALSE.equals(req.getManualChecksAllowed()))
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
        LocalTime storedStart = req.getAllowedStartTime() != null
                ? req.getAllowedStartTime() : DEFAULT_ALLOWED_START;
        LocalTime storedEnd = req.getAllowedEndTime() != null
                ? req.getAllowedEndTime() : DEFAULT_ALLOWED_END;
        validateSchedulingFields(req.getWindowMode(), req.getAllowedStartTime(), req.getAllowedEndTime(),
                req.getChecksPerShift(), req.getMinIntervalMinutes());

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));
        validateShiftCoverage(tenantId, siteId, req.getWindowMode(), storedStart,
                storedEnd, req.getChecksPerShift(), req.getMinIntervalMinutes());

        if (configRepository.findBySite(tenantId, siteId).isPresent()) {
            throw new DuplicateResourceException(
                    "A random check configuration for this site already exists. Use PUT to update it.");
        }

        RandomCheckConfig config = RandomCheckConfig.builder()
                .tenantId(tenantId)
                .siteId(siteId)
                .checksPerShift(req.getChecksPerShift())
                .minIntervalMinutes(req.getMinIntervalMinutes())
                .allowedStartTime(storedStart)
                .allowedEndTime(storedEnd)
                .windowMode(req.getWindowMode())
                .checkMode(req.getCheckMode())
                .applicableRoles(toRolesString(req.getApplicableRoles()))
                .responseWindowSeconds(req.getResponseWindowSeconds())
                .failureEscalationThreshold(
                        req.getFailureEscalationThreshold() != null ? req.getFailureEscalationThreshold() : 0)
                .isActive(true)
                .manualChecksAllowed(!Boolean.FALSE.equals(req.getManualChecksAllowed()))
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
     * if one exists (including an inactive override that explicitly disables the site), else the
     * tenant default — the exact same resolution order
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
        String effectiveWindowMode = req.getWindowMode() != null ? req.getWindowMode() : config.getWindowMode();
        validateSchedulingFields(effectiveWindowMode, effectiveStart, effectiveEnd, effectiveChecks, effectiveInterval);
        validateShiftCoverage(tenantId, config.getSiteId(), effectiveWindowMode, effectiveStart,
                effectiveEnd, effectiveChecks, effectiveInterval);

        Map<String, Object> oldValue = configSnapshot(config);

        if (req.getChecksPerShift() != null) config.setChecksPerShift(req.getChecksPerShift());
        if (req.getMinIntervalMinutes() != null) config.setMinIntervalMinutes(req.getMinIntervalMinutes());
        if (req.getAllowedStartTime() != null) config.setAllowedStartTime(req.getAllowedStartTime());
        if (req.getAllowedEndTime() != null) config.setAllowedEndTime(req.getAllowedEndTime());
        if (req.getWindowMode() != null) config.setWindowMode(req.getWindowMode());
        if (req.getCheckMode() != null) config.setCheckMode(req.getCheckMode());
        if (req.getApplicableRoles() != null) config.setApplicableRoles(toRolesString(req.getApplicableRoles()));
        if (req.getResponseWindowSeconds() != null) config.setResponseWindowSeconds(req.getResponseWindowSeconds());
        if (req.getIsActive() != null) config.setActive(req.getIsActive());
        if (req.getFailureEscalationThreshold() != null) config.setFailureEscalationThreshold(req.getFailureEscalationThreshold());
        if (req.getManualChecksAllowed() != null) config.setManualChecksAllowed(req.getManualChecksAllowed());

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

    private void validateSchedulingFields(String windowMode, LocalTime startTime, LocalTime endTime,
                                          int checksPerShift, int minIntervalMinutes) {
        if (!"full_shift".equals(windowMode) && !"custom_window".equals(windowMode)) {
            throw new IllegalArgumentException("window_mode must be full_shift or custom_window");
        }
        if ("full_shift".equals(windowMode)) return;
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException(
                    "custom_window requires both allowed_start_time and allowed_end_time");
        }
        long windowMinutes = minutesBetweenPossiblyOvernight(startTime, endTime);
        // checks_per_shift checks need (checks_per_shift - 1) intervals between them
        long minWindowNeeded = (long) (checksPerShift - 1) * minIntervalMinutes;
        if (minIntervalMinutes > 0 && checksPerShift > 1 && windowMinutes < minWindowNeeded) {
            throw new IllegalArgumentException(String.format(
                    "Time window (%d min) is too short: %d checks with %d min intervals require at least %d min",
                    windowMinutes, checksPerShift, minIntervalMinutes, minWindowNeeded));
        }
    }

    /** Reject a policy that would silently leave any inherited shift uncovered or unable to fit
     * the requested number of checks. A disabled shift is an explicit exclusion and is skipped. */
    private void validateShiftCoverage(UUID tenantId, UUID siteId, String windowMode,
                                       LocalTime start, LocalTime end,
                                       int checksPerShift, int minIntervalMinutes) {
        List<Shift> shifts = siteId == null
                ? shiftRepository.findByTenantIdAndStatusAndDeletedAtIsNullOrderByStartTimeAsc(tenantId, "active")
                : shiftRepository.findBySiteIdAndStatusAndDeletedAtIsNullOrderByStartTimeAsc(siteId, "active");
        long required = (long) (checksPerShift - 1) * minIntervalMinutes;
        List<String> invalid = shifts.stream()
                .filter(s -> !"disabled".equals(s.getRandomCheckPolicy()))
                .filter(s -> siteId != null || configRepository.findBySite(tenantId, s.getSiteId()).isEmpty())
                .filter(s -> effectiveWindowMinutes(s, windowMode, start, end) < required)
                .map(s -> String.format("%s (%s–%s%s)", s.getName(), s.getStartTime(), s.getEndTime(),
                        s.isAllowOvernight() ? ", qua đêm" : ""))
                .toList();
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "Policy cannot fit %d checks with %d-minute spacing in these shifts: %s. "
                            + "Use full_shift, reduce checks/spacing, adjust the custom window, or explicitly disable random checks for those shifts.",
                    checksPerShift, minIntervalMinutes, String.join(", ", invalid)));
        }
    }

    private long effectiveWindowMinutes(Shift shift, String mode, LocalTime start, LocalTime end) {
        LocalDate base = LocalDate.of(2000, 1, 1);
        LocalDateTime shiftStart = base.atTime(shift.getStartTime());
        LocalDateTime shiftEnd = base.atTime(shift.getEndTime());
        if (shift.isAllowOvernight() || !shiftEnd.isAfter(shiftStart)) shiftEnd = shiftEnd.plusDays(1);
        if ("full_shift".equals(mode)) return Duration.between(shiftStart, shiftEnd).toMinutes();

        LocalDateTime configStart = base.atTime(start);
        LocalDateTime configEnd = base.atTime(end);
        if (!configEnd.isAfter(configStart)) configEnd = configEnd.plusDays(1);
        LocalDateTime overlapStart = shiftStart.isAfter(configStart) ? shiftStart : configStart;
        LocalDateTime overlapEnd = shiftEnd.isBefore(configEnd) ? shiftEnd : configEnd;
        return overlapEnd.isAfter(overlapStart) ? Duration.between(overlapStart, overlapEnd).toMinutes() : -1;
    }

    /** Prevent shift creation/editing from invalidating an already-valid random-check policy. */
    @Transactional(readOnly = true)
    public void assertShiftCompatible(UUID tenantId, Shift shift) {
        if (!"active".equals(shift.getStatus()) || "disabled".equals(shift.getRandomCheckPolicy())) {
            return;
        }
        Optional<RandomCheckConfig> configOpt = configRepository.findBySite(tenantId, shift.getSiteId());
        if (configOpt.isEmpty()) configOpt = configRepository.findTenantDefault(tenantId);
        if (configOpt.isEmpty()) {
            if ("enabled".equals(shift.getRandomCheckPolicy())) {
                throw new IllegalArgumentException(
                        "This shift explicitly enables random checks but its site/company has no random-check config");
            }
            return;
        }

        RandomCheckConfig config = configOpt.get();
        if (!config.isActive() && !"enabled".equals(shift.getRandomCheckPolicy())) return;
        long required = (long) (config.getChecksPerShift() - 1) * config.getMinIntervalMinutes();
        long available = effectiveWindowMinutes(shift, config.getWindowMode(),
                config.getAllowedStartTime(), config.getAllowedEndTime());
        if (available < required) {
            throw new IllegalArgumentException(String.format(
                    "Shift '%s' would leave a random-check coverage gap: available=%d minutes, "
                            + "required=%d minutes. Adjust the shift/config or explicitly disable random checks for this shift.",
                    shift.getName(), Math.max(0, available), required));
        }
    }

    private long minutesBetweenPossiblyOvernight(LocalTime start, LocalTime end) {
        LocalDate base = LocalDate.of(2000, 1, 1);
        LocalDateTime from = base.atTime(start);
        LocalDateTime to = base.atTime(end);
        if (!to.isAfter(from)) to = to.plusDays(1);
        return Duration.between(from, to).toMinutes();
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
        map.put("windowMode", c.getWindowMode());
        map.put("checkMode", c.getCheckMode());
        map.put("applicableRoles", c.getApplicableRoles());
        map.put("responseWindowSeconds", c.getResponseWindowSeconds());
        map.put("failureEscalationThreshold", c.getFailureEscalationThreshold());
        map.put("isActive", c.isActive());
        map.put("manualChecksAllowed", c.isManualChecksAllowed());
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
                .windowMode(c.getWindowMode())
                .checkMode(c.getCheckMode())
                .applicableRoles(roles)
                .responseWindowSeconds(c.getResponseWindowSeconds())
                .failureEscalationThreshold(c.getFailureEscalationThreshold())
                .isActive(c.isActive())
                .manualChecksAllowed(c.isManualChecksAllowed())
                .createdBy(c.getCreatedBy())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .resolvedFrom(resolvedFrom)
                .build();
    }
}
