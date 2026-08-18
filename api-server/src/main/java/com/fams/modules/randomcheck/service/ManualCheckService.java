package com.fams.modules.randomcheck.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.assignment.util.DayOfWeekBitmask;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.randomcheck.dto.request.ManualCheckRequest;
import com.fams.modules.randomcheck.entity.RandomCheckConfig;
import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.repository.RandomCheckConfigRepository;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.subscription.service.PlanLimitEnforcementService;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class ManualCheckService {

    private static final String[] VALID_MODES =
            {"location_only", "location_face", "location_face_liveness"};

    private final AssignmentRepository assignmentRepository;
    private final RandomCheckConfigRepository configRepository;
    private final ScheduledCheckRepository scheduledCheckRepository;
    private final SiteRepository siteRepository;
    private final PlanLimitEnforcementService planLimitEnforcementService;
    private final RandomCheckDispatchService randomCheckDispatchService;
    private final FaceProfileRepository faceProfileRepository;
    private final AuditLogService auditLogService;

    public ManualCheckService(AssignmentRepository assignmentRepository,
                              RandomCheckConfigRepository configRepository,
                              ScheduledCheckRepository scheduledCheckRepository,
                              SiteRepository siteRepository,
                              PlanLimitEnforcementService planLimitEnforcementService,
                              @Lazy RandomCheckDispatchService randomCheckDispatchService,
                              FaceProfileRepository faceProfileRepository,
                              AuditLogService auditLogService) {
        this.assignmentRepository = assignmentRepository;
        this.configRepository = configRepository;
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.siteRepository = siteRepository;
        this.planLimitEnforcementService = planLimitEnforcementService;
        this.randomCheckDispatchService = randomCheckDispatchService;
        this.faceProfileRepository = faceProfileRepository;
        this.auditLogService = auditLogService;
    }

    /** Manual triggers are never rate-limited (audit 2026-08-03 — see the repository query's
     *  javadoc for why), but HR should still be able to see "how many times today" so they can
     *  make an informed call. Exposed separately from #trigger so the controller can report the
     *  post-trigger count without changing the entity return type. */
    @Transactional(readOnly = true)
    public int countManualTriggersToday(UUID tenantId, UUID employeeId, LocalDate date) {
        return (int) scheduledCheckRepository
                .countByTenantIdAndEmployeeIdAndCheckDateAndCheckIndexLessThanEqualAndDeletedAtIsNull(
                        tenantId, employeeId, date, 0);
    }

    /**
     * Creates and immediately dispatches a manual random check for a specific employee at a site.
     * Bypasses the Redis queue — the check is created with status='sent' and expires immediately
     * based on the configured responseWindowSeconds.
     *
     * Validations:
     *  - Employee must have an active assignment at the specified site today.
     *  - A random check config must exist (site override or tenant default).
     *  - checkMode override (if provided) must be a valid value.
     */
    @Transactional
    public ScheduledCheck trigger(UUID tenantId, ManualCheckRequest request, UUID triggeredBy) {
        UUID siteId = request.getSiteId();
        UUID employeeId = request.getEmployeeId();
        LocalDate today = LocalDate.now();

        // Validate site belongs to tenant
        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        // Validate employee has an active assignment at this site today
        Assignment assignment = assignmentRepository
                .findActiveAssignmentByEmployeeAndSite(tenantId, siteId, employeeId, today,
                        DayOfWeekBitmask.bitForDate(today))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Employee " + employeeId + " has no active assignment at site " + siteId + " today"));

        // Resolve config: site override → tenant default
        Optional<RandomCheckConfig> configOpt = configRepository.findBySite(tenantId, siteId);
        if (configOpt.isEmpty()) configOpt = configRepository.findTenantDefault(tenantId);
        RandomCheckConfig config = configOpt
                .orElseThrow(() -> new IllegalArgumentException(
                        "No random check config found for tenant " + tenantId));

        // Enforce monthly random check quota
        planLimitEnforcementService.assertRandomCheckLimit(tenantId);

        // Validate and apply checkMode override
        String checkMode = resolveCheckMode(request.getCheckMode(), config.getCheckMode());

        // Same Face ID enrollment fail-safe as ScheduledCheckGeneratorService (audit 2026-08-02)
        // — but only when HR did NOT explicitly request a face/liveness mode themselves. An
        // explicit override is a deliberate HR decision (e.g. testing whether this employee can
        // even complete the check) and must be respected as requested; only the config's
        // IMPLICIT default gets silently downgraded, same rule as the nightly generator.
        boolean explicitModeRequested = request.getCheckMode() != null && !request.getCheckMode().isBlank();
        if (!explicitModeRequested && !"location_only".equals(checkMode)) {
            boolean faceEnrolled = faceProfileRepository
                    .findByEmployeeIdAndTenantId(employeeId, tenantId)
                    .map(fp -> "enrolled".equals(fp.getStatus()))
                    .orElse(false);
            if (!faceEnrolled) {
                log.info("Downgrading manual check mode to location_only for employeeId={} — "
                                + "config default '{}' but employee has no approved Face ID",
                        employeeId, checkMode);
                checkMode = "location_only";
            }
        }

        // Build config snapshot (same format as ScheduledCheckGeneratorService)
        String snapshot = buildSnapshot(config, checkMode);

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plusSeconds(config.getResponseWindowSeconds());

        // Use check_index = 0 as a sentinel for manual checks.
        // If 0 is already taken (another manual check today), use negative auto-decrement.
        int checkIndex = nextManualIndex(assignment.getId(), today);

        ScheduledCheck check = ScheduledCheck.builder()
                .tenantId(tenantId)
                .assignmentId(assignment.getId())
                .employeeId(employeeId)
                .siteId(siteId)
                .shiftId(assignment.getShiftId())
                .configId(config.getId())
                .configSnapshot(snapshot)
                .checkDate(today)
                .checkIndex(checkIndex)
                .scheduledAt(now)
                .expiresAt(expiresAt)
                .status("sent")  // immediately dispatched — no Redis queue needed
                .sentAt(now)  // #100 (2026-08-18): this path bypasses RandomCheckDispatchService
                              // .dispatch() (which is the only other place sentAt gets set), so
                              // set it here too — same instant as scheduledAt for a manual check.
                .manualReason(request.getReason())
                .triggeredBy(triggeredBy)
                .build();

        scheduledCheckRepository.save(check);

        int countToday = countManualTriggersToday(tenantId, employeeId, today);

        log.info("Manual check triggered: checkId={} employeeId={} siteId={} mode={} reason={} by={} "
                        + "countToday={}",
                check.getId(), employeeId, siteId, checkMode, request.getReason(), triggeredBy, countToday);

        auditLogService.record(
                tenantId, triggeredBy, null,
                "ScheduledCheck", check.getId().toString(), "manual_random_check_triggered",
                null,
                Map.of("employeeId", employeeId.toString(), "siteId", siteId.toString(),
                       "checkMode", checkMode, "reason", request.getReason() == null ? "" : request.getReason(),
                       "triggerCountToday", countToday),
                com.fams.shared.security.HttpRequestUtils.currentRequestId(), null, null);

        randomCheckDispatchService.sendNotification(check);

        return check;
    }

    /** Returns the next available check_index for manual checks (0, -1, -2, ...). */
    private int nextManualIndex(UUID assignmentId, LocalDate date) {
        // Manual checks use non-positive indices to avoid collision with scheduled checks (1, 2, ...)
        // Start at 0, then -1, -2, etc.
        int candidate = 0;
        while (scheduledCheckRepository.existsByAssignmentIdAndCheckDateAndCheckIndex(
                assignmentId, date, candidate)) {
            candidate--;
        }
        return candidate;
    }

    private String resolveCheckMode(String requested, String configDefault) {
        if (requested == null || requested.isBlank()) return configDefault;
        for (String valid : VALID_MODES) {
            if (valid.equals(requested)) return requested;
        }
        throw new IllegalArgumentException(
                "Invalid checkMode '" + requested + "'. Must be one of: location_only, location_face, location_face_liveness");
    }

    private String buildSnapshot(RandomCheckConfig c, String checkMode) {
        return String.format(
                "{\"configId\":\"%s\",\"checkMode\":\"%s\",\"checksPerShift\":%d," +
                "\"minIntervalMinutes\":%d,\"allowedStartTime\":\"%s\"," +
                "\"allowedEndTime\":\"%s\",\"responseWindowSeconds\":%d,\"manual\":true}",
                c.getId(), checkMode, c.getChecksPerShift(),
                c.getMinIntervalMinutes(), c.getAllowedStartTime(), c.getAllowedEndTime(),
                c.getResponseWindowSeconds());
    }
}
