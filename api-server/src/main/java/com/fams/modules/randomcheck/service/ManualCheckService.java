package com.fams.modules.randomcheck.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.randomcheck.dto.request.ManualCheckRequest;
import com.fams.modules.randomcheck.dto.response.ManualCheckCandidateResponse;
import com.fams.modules.randomcheck.entity.RandomCheckConfig;
import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.repository.RandomCheckConfigRepository;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.subscription.service.PlanLimitEnforcementService;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final CheckinRepository checkinRepository;
    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;

    public ManualCheckService(AssignmentRepository assignmentRepository,
                              RandomCheckConfigRepository configRepository,
                              ScheduledCheckRepository scheduledCheckRepository,
                              SiteRepository siteRepository,
                              PlanLimitEnforcementService planLimitEnforcementService,
                              @Lazy RandomCheckDispatchService randomCheckDispatchService,
                              FaceProfileRepository faceProfileRepository,
                              AuditLogService auditLogService,
                              CheckinRepository checkinRepository,
                              ShiftRepository shiftRepository,
                              EmployeeRepository employeeRepository) {
        this.assignmentRepository = assignmentRepository;
        this.configRepository = configRepository;
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.siteRepository = siteRepository;
        this.planLimitEnforcementService = planLimitEnforcementService;
        this.randomCheckDispatchService = randomCheckDispatchService;
        this.faceProfileRepository = faceProfileRepository;
        this.auditLogService = auditLogService;
        this.checkinRepository = checkinRepository;
        this.shiftRepository = shiftRepository;
        this.employeeRepository = employeeRepository;
    }

    /** Returns only employees who can pass the same session/policy guards used by trigger(). */
    @Transactional(readOnly = true)
    public List<ManualCheckCandidateResponse> listEligibleCandidates(UUID tenantId, UUID siteId) {
        Site site = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));
        OffsetDateTime now = OffsetDateTime.now(safeZone(site.getTimezone()));

        Optional<RandomCheckConfig> configOpt = configRepository.findBySite(tenantId, siteId);
        if (configOpt.isEmpty()) configOpt = configRepository.findTenantDefault(tenantId);
        if (configOpt.isEmpty()) return List.of();
        RandomCheckConfig config = configOpt.get();

        List<CheckinRecord> sessions = checkinRepository
                .findUnexpiredOpenSessionsBySite(tenantId, siteId, now)
                .stream()
                .filter(session -> isManualAllowed(session.getShiftId(), config))
                .toList();
        if (sessions.isEmpty()) return List.of();

        Map<UUID, Employee> employees = employeeRepository
                .findAllByTenantIdAndIdInAndDeletedAtIsNull(
                        tenantId, sessions.stream().map(CheckinRecord::getEmployeeId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));

        return sessions.stream()
                .map(session -> {
                    Employee employee = employees.get(session.getEmployeeId());
                    if (employee == null || !"active".equals(employee.getStatus())) return null;
                    Shift shift = shiftRepository.findById(session.getShiftId()).orElse(null);
                    return ManualCheckCandidateResponse.builder()
                            .employeeId(employee.getId())
                            .employeeName((employee.getLastName() + " " + employee.getFirstName()).trim())
                            .employeeCode(employee.getEmployeeCode())
                            .checkinId(session.getId())
                            .checkInAt(session.getCheckInAt())
                            .shiftId(session.getShiftId())
                            .shiftName(shift == null ? null : shift.getName())
                            .build();
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ManualCheckCandidateResponse::getEmployeeName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private boolean isManualAllowed(UUID shiftId, RandomCheckConfig config) {
        if (shiftId == null) return false;
        return shiftRepository.findById(shiftId)
                .map(shift -> "enabled".equals(shift.getManualCheckPolicy())
                        || (!"disabled".equals(shift.getManualCheckPolicy())
                        && config.isManualChecksAllowed()))
                .orElse(false);
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
     *  - Employee must have a non-expired open check-in at the specified site.
     *  - A random check config must exist (site override or tenant default).
     *  - checkMode override (if provided) must be a valid value.
     */
    @Transactional
    public ScheduledCheck trigger(UUID tenantId, ManualCheckRequest request, UUID triggeredBy) {
        UUID siteId = request.getSiteId();
        UUID employeeId = request.getEmployeeId();
        Site site = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));
        ZoneId zone = safeZone(site.getTimezone());
        OffsetDateTime now = OffsetDateTime.now(zone);
        LocalDate today = now.toLocalDate();

        // A targeted presence check only makes business sense while the employee is actually
        // checked in. An active assignment alone used to allow HR to notify absent/off-shift staff.
        CheckinRecord openSession = checkinRepository
                .findByTenantIdAndEmployeeIdAndCheckOutAtIsNullAndSessionClosedAtIsNullAndDeletedAtIsNull(
                        tenantId, employeeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Employee is not currently checked in; an immediate random check cannot be sent"));
        if (!siteId.equals(openSession.getSiteId())) {
            throw new IllegalArgumentException("Employee is currently checked in at a different site");
        }
        if (openSession.getSessionExpiresAt() != null && !openSession.getSessionExpiresAt().isAfter(now)) {
            throw new IllegalArgumentException("Employee's check-in session has already expired");
        }
        Assignment assignment = assignmentRepository.findById(openSession.getAssignmentId())
                .filter(a -> tenantId.equals(a.getTenantId()) && siteId.equals(a.getSiteId())
                        && employeeId.equals(a.getEmployeeId()))
                .orElseThrow(() -> new IllegalArgumentException("Open check-in has no valid assignment"));
        Shift shift = shiftRepository.findById(assignment.getShiftId())
                .orElseThrow(() -> new IllegalArgumentException("Assignment has no valid shift"));

        // Resolve config: site override → tenant default
        Optional<RandomCheckConfig> configOpt = configRepository.findBySite(tenantId, siteId);
        if (configOpt.isEmpty()) configOpt = configRepository.findTenantDefault(tenantId);
        RandomCheckConfig config = configOpt
                .orElseThrow(() -> new IllegalArgumentException(
                        "No random check config found for tenant " + tenantId));

        boolean manualAllowed = "enabled".equals(shift.getManualCheckPolicy())
                || (!"disabled".equals(shift.getManualCheckPolicy()) && config.isManualChecksAllowed());
        if (!manualAllowed) {
            throw new IllegalArgumentException("Manual random checks are disabled for this shift/site policy");
        }

        // Enforce monthly random check quota
        planLimitEnforcementService.assertRandomCheckLimit(tenantId, triggeredBy);

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
                "\"allowedEndTime\":\"%s\",\"windowMode\":\"%s\"," +
                "\"responseWindowSeconds\":%d,\"manual\":true}",
                c.getId(), checkMode, c.getChecksPerShift(),
                c.getMinIntervalMinutes(), c.getAllowedStartTime(), c.getAllowedEndTime(),
                c.getWindowMode(),
                c.getResponseWindowSeconds());
    }

    private ZoneId safeZone(String timezone) {
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
        } catch (Exception ex) {
            log.warn("Invalid site timezone '{}', falling back to UTC", timezone);
            return ZoneId.of("UTC");
        }
    }
}
