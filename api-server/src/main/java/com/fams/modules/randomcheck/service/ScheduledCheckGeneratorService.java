package com.fams.modules.randomcheck.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.assignment.util.DayOfWeekBitmask;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.randomcheck.entity.RandomCheckConfig;
import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.redis.RandomCheckDispatchQueue;
import com.fams.modules.randomcheck.repository.RandomCheckConfigRepository;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.subscription.service.PlanLimitEnforcementService;
import com.fams.shared.time.VietnamTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
public class ScheduledCheckGeneratorService {

    private final AssignmentRepository assignmentRepository;
    private final RandomCheckConfigRepository configRepository;
    private final ScheduledCheckRepository scheduledCheckRepository;
    private final ShiftRepository shiftRepository;
    private final SiteRepository siteRepository;
    private final RandomCheckDispatchQueue dispatchQueue;
    private final PlanLimitEnforcementService planLimitEnforcementService;
    private final FaceProfileRepository faceProfileRepository;
    private final CheckinRepository checkinRepository;
    private final SecureRandom random = new SecureRandom();

    public ScheduledCheckGeneratorService(AssignmentRepository assignmentRepository,
                                          RandomCheckConfigRepository configRepository,
                                          ScheduledCheckRepository scheduledCheckRepository,
                                          ShiftRepository shiftRepository,
                                          SiteRepository siteRepository,
                                          RandomCheckDispatchQueue dispatchQueue,
                                          PlanLimitEnforcementService planLimitEnforcementService,
                                          FaceProfileRepository faceProfileRepository,
                                          CheckinRepository checkinRepository) {
        this.assignmentRepository = assignmentRepository;
        this.configRepository = configRepository;
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.shiftRepository = shiftRepository;
        this.siteRepository = siteRepository;
        this.dispatchQueue = dispatchQueue;
        this.planLimitEnforcementService = planLimitEnforcementService;
        this.faceProfileRepository = faceProfileRepository;
        this.checkinRepository = checkinRepository;
    }

    /**
     * Generates scheduled checks for every active assignment (across all tenants) on the given date.
     * Called by the daily cron job.
     */
    @Transactional
    public int generateForDate(LocalDate date) {
        List<Assignment> assignments = assignmentRepository.findAllActiveAssignmentsWithShiftForDate(
                date, DayOfWeekBitmask.bitForDate(date));
        int generated = 0;
        for (Assignment assignment : assignments) {
            generated += generateForAssignment(assignment, date);
        }
        log.info("Daily generation for {} — processed {} assignments, created {} scheduled checks",
                date, assignments.size(), generated);
        return generated;
    }

    /**
     * Generates scheduled checks for a single tenant on the given date.
     * Used by the manual trigger endpoint.
     */
    @Transactional
    public int generateForTenantAndDate(UUID tenantId, LocalDate date) {
        List<Assignment> assignments = assignmentRepository.findActiveAssignmentsWithShiftForDate(
                tenantId, date, DayOfWeekBitmask.bitForDate(date));
        int generated = 0;
        for (Assignment assignment : assignments) {
            generated += generateForAssignment(assignment, date);
        }
        log.info("Tenant generation tenantId={} date={} — {} assignments, {} checks created",
                tenantId, date, assignments.size(), generated);
        return generated;
    }

    /**
     * Restart-safe and timezone-safe generation entrypoint. Candidate dates cover every legal
     * UTC offset; each assignment is generated only for the date that is actually "today" at
     * its site's timezone.
     */
    @Transactional
    public int ensureSchedulesForCurrentLocalDates(OffsetDateTime now) {
        LocalDate utcDate = now.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDate();
        Set<UUID> visitedAssignments = new HashSet<>();
        int generated = 0;

        for (LocalDate candidate : List.of(utcDate.minusDays(1), utcDate, utcDate.plusDays(1))) {
            List<Assignment> assignments = assignmentRepository.findAllActiveAssignmentsWithShiftForDate(
                    candidate, DayOfWeekBitmask.bitForDate(candidate));
            for (Assignment assignment : assignments) {
                Site site = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                                assignment.getSiteId(), assignment.getTenantId())
                        .orElse(null);
                ZoneId zone = safeZone(site != null ? site.getTimezone() : VietnamTime.ID);
                if (!candidate.equals(now.atZoneSameInstant(zone).toLocalDate())) {
                    continue;
                }
                if (!visitedAssignments.add(assignment.getId())) {
                    continue;
                }
                boolean hasMatchingOpenSession = checkinRepository
                        .findByTenantIdAndEmployeeIdAndCheckOutAtIsNullAndSessionClosedAtIsNullAndDeletedAtIsNull(
                                assignment.getTenantId(), assignment.getEmployeeId())
                        .filter(session -> assignment.getId().equals(session.getAssignmentId()))
                        .filter(session -> session.getSessionExpiresAt() == null
                                || session.getSessionExpiresAt().isAfter(now))
                        .isPresent();
                generated += generateForAssignment(assignment, candidate, now, hasMatchingOpenSession);
            }
        }
        log.debug("Random-check schedule ensure at {} created {} check(s)", now, generated);
        return generated;
    }

    /**
     * Generates scheduled check records for one assignment on a given date.
     * Skips silently if checks were already generated for this assignment+date.
     * Returns the number of ScheduledCheck records created.
     */
    @Transactional
    public int generateForAssignment(Assignment assignment, LocalDate date) {
        return generateForAssignment(assignment, date, null, false);
    }

    private int generateForAssignment(Assignment assignment, LocalDate date,
                                      OffsetDateTime notBefore, boolean replenishReplaceableCancellations) {
        // Idempotency guard — skip if already generated
        if (!replenishReplaceableCancellations
                && scheduledCheckRepository.existsByAssignmentIdAndCheckDateAndCheckIndexGreaterThanAndDeletedAtIsNull(
                assignment.getId(), date, 0)) {
            log.debug("Skipping assignment={} date={} — already generated", assignment.getId(), date);
            return 0;
        }

        // Resolve the applicable config: site override first, fall back to tenant default
        Optional<RandomCheckConfig> configOpt = configRepository
                .findBySite(assignment.getTenantId(), assignment.getSiteId());
        if (configOpt.isEmpty()) {
            configOpt = configRepository.findTenantDefault(assignment.getTenantId());
        }
        if (configOpt.isEmpty()) {
            log.debug("No random check config for tenantId={} siteId={} — skipping assignment={}",
                    assignment.getTenantId(), assignment.getSiteId(), assignment.getId());
            return 0;
        }
        RandomCheckConfig config = configOpt.get();

        List<ScheduledCheck> existingAutomatic = replenishReplaceableCancellations
                ? scheduledCheckRepository.findByAssignmentAndDate(assignment.getId(), date).stream()
                    .filter(check -> check.getCheckIndex() > 0)
                    .toList()
                : List.of();
        long alreadyCommitted = existingAutomatic.stream()
                .filter(check -> !isReplaceableSystemCancellation(check))
                .count();
        int desiredRemaining = Math.max(0, config.getChecksPerShift() - (int) alreadyCommitted);
        if (replenishReplaceableCancellations && desiredRemaining == 0) {
            return 0;
        }

        // Role filter — if applicableRoles is non-empty, only apply to matching roles
        if (!config.getApplicableRoles().isBlank()) {
            List<String> allowed = Arrays.asList(config.getApplicableRoles().split(","));
            if (!allowed.contains(assignment.getRole())) {
                log.debug("Assignment role '{}' not in applicableRoles '{}' — skipping assignment={}",
                        assignment.getRole(), config.getApplicableRoles(), assignment.getId());
                return 0;
            }
        }

        // Resolve site timezone
        String timezone = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                        assignment.getSiteId(), assignment.getTenantId())
                .map(Site::getTimezone)
                .orElse(VietnamTime.ID);
        ZoneId zone = safeZone(timezone);

        // Bound the configured window by the assignment's actual shift hours — found via audit
        // (2026-07-31): allowedStartTime/allowedEndTime previously came ONLY from the config,
        // fully independent of the employee's real working hours, so a config window could
        // silently schedule checks outside the shift (e.g. a day-window config against a night
        // shift). This query already filters to assignments WITH a shift_id, so a lookup miss
        // here means the shift was deleted after the assignment was created — treat as no shift.
        Shift shift = shiftRepository.findById(assignment.getShiftId()).orElse(null);
        if (shift == null) {
            log.warn("Missing shift={} for assignment={} — random checks cannot be scheduled",
                    assignment.getShiftId(), assignment.getId());
            return 0;
        }
        if ("disabled".equals(shift.getRandomCheckPolicy())
                || (!config.isActive() && !"enabled".equals(shift.getRandomCheckPolicy()))) {
            return 0;
        }
        Optional<EffectiveWindow> windowOpt = resolveEffectiveWindow(config, shift, date, zone);
        if (windowOpt.isEmpty()) {
            log.info("Configured random-check window [{}, {}] does not overlap assignment's shift "
                            + "hours [{}, {}] — skipping assignment={} shiftId={}",
                    config.getAllowedStartTime(), config.getAllowedEndTime(),
                    shift != null ? shift.getStartTime() : null, shift != null ? shift.getEndTime() : null,
                    assignment.getId(), assignment.getShiftId());
            return 0;
        }
        EffectiveWindow window = windowOpt.get();

        // Enforce monthly random check quota
        int remaining = planLimitEnforcementService.getRemainingRandomChecks(assignment.getTenantId());
        if (remaining <= 0) {
            log.debug("Random check monthly quota exhausted for tenantId={} — skipping assignment={}",
                    assignment.getTenantId(), assignment.getId());
            return 0;
        }
        int checksToGenerate = Math.min(
                replenishReplaceableCancellations ? desiredRemaining : config.getChecksPerShift(),
                remaining);

        // A restart/mid-day catch-up must not dispatch checks that are already in the past.
        // Keep a one-minute runway for persistence/queueing and reduce the count if the remaining
        // window cannot fit the configured minimum spacing.
        OffsetDateTime generationStart = window.start();
        if (notBefore != null) {
            OffsetDateTime earliestFuture = notBefore.atZoneSameInstant(zone)
                    .truncatedTo(ChronoUnit.MINUTES)
                    .plusMinutes(1)
                    .toOffsetDateTime();
            if (!earliestFuture.isBefore(window.end())) {
                log.debug("Random-check window ended for assignment={} date={} — no late catch-up",
                        assignment.getId(), date);
                return 0;
            }
            if (earliestFuture.isAfter(generationStart)) {
                generationStart = earliestFuture;
            }
            Optional<OffsetDateTime> latestCommittedSlot = existingAutomatic.stream()
                    .filter(check -> !isReplaceableSystemCancellation(check))
                    .map(ScheduledCheck::getScheduledAt)
                    .max(Comparator.naturalOrder());
            if (latestCommittedSlot.isPresent()) {
                OffsetDateTime afterLatest = latestCommittedSlot.get()
                        .plusMinutes(config.getMinIntervalMinutes());
                if (afterLatest.isAfter(generationStart)) {
                    generationStart = afterLatest;
                }
            }
            int remainingWindowMinutes = (int) java.time.Duration.between(generationStart, window.end()).toMinutes();
            int maxFeasible = config.getMinIntervalMinutes() == 0
                    ? checksToGenerate
                    : remainingWindowMinutes / config.getMinIntervalMinutes() + 1;
            checksToGenerate = Math.min(checksToGenerate, maxFeasible);
            if (checksToGenerate <= 0) {
                return 0;
            }
        }

        long availableMinutes = java.time.Duration.between(generationStart, window.end()).toMinutes();
        long requiredMinutes = (long) (checksToGenerate - 1) * config.getMinIntervalMinutes();
        if (availableMinutes < requiredMinutes) {
            log.error("Random-check policy is not feasible for assignment={} shiftId={}: available={}min, "
                            + "required={}min. No out-of-shift checks will be generated.",
                    assignment.getId(), shift.getId(), availableMinutes, requiredMinutes);
            return 0;
        }

        // Generate N random check times within the effective (config ∩ shift) window
        List<OffsetDateTime> checkTimes = generateCheckTimes(
                generationStart, window.end(),
                checksToGenerate, config.getMinIntervalMinutes());

        // Fail-safe (found via audit 2026-08-02): a location_face/location_face_liveness config
        // used to be snapshotted as-is regardless of whether this employee was ever enrolled and
        // HR-approved for Face ID — the check would still fire, the employee would be asked to
        // respond, and CheckResponseService would then raise a face_fail violation purely because
        // "no enrolled profile", penalizing them for something they were never approved to do.
        // This mirrors the fail-safe already established for regular check-in's checkin_policy
        // (an employee without an approved face profile is never blocked/blamed by a face
        // requirement they can't satisfy) — downgrade the SNAPSHOT only (not the tenant/site
        // config itself) to location_only for this specific check when enrollment is missing, so
        // location auditing still happens but no bogus biometric failure can be raised.
        String effectiveCheckMode = config.getCheckMode();
        if (!"location_only".equals(effectiveCheckMode)) {
            boolean faceEnrolled = faceProfileRepository
                    .findByEmployeeIdAndTenantId(assignment.getEmployeeId(), assignment.getTenantId())
                    .map(fp -> "enrolled".equals(fp.getStatus()))
                    .orElse(false);
            if (!faceEnrolled) {
                log.info("Downgrading random-check mode to location_only for assignment={} "
                                + "employeeId={} — configured mode '{}' but employee has no approved Face ID",
                        assignment.getId(), assignment.getEmployeeId(), config.getCheckMode());
                effectiveCheckMode = "location_only";
            }
        }

        String snapshot = buildSnapshot(config, effectiveCheckMode);

        int nextCheckIndex = existingAutomatic.stream()
                .mapToInt(ScheduledCheck::getCheckIndex)
                .max()
                .orElse(0) + 1;
        List<ScheduledCheck> checks = new ArrayList<>();
        for (int i = 0; i < checkTimes.size(); i++) {
            OffsetDateTime scheduledAt = checkTimes.get(i);
            OffsetDateTime expiresAt = scheduledAt.plusSeconds(config.getResponseWindowSeconds());

            checks.add(ScheduledCheck.builder()
                    .tenantId(assignment.getTenantId())
                    .assignmentId(assignment.getId())
                    .employeeId(assignment.getEmployeeId())
                    .siteId(assignment.getSiteId())
                    .shiftId(assignment.getShiftId())
                    .configId(config.getId())
                    .configSnapshot(snapshot)
                    .checkDate(date)
                    .checkIndex(replenishReplaceableCancellations ? nextCheckIndex + i : i + 1)
                    .scheduledAt(scheduledAt)
                    .expiresAt(expiresAt)
                    .status("pending")
                    .build());
        }

        scheduledCheckRepository.saveAll(checks);

        // Register each check in the Redis dispatch queue so it fires at scheduled_at
        checks.forEach(c -> dispatchQueue.enqueue(c.getId(), c.getScheduledAt()));

        log.info("Generated {} scheduled checks for assignment={} date={}", checks.size(), assignment.getId(), date);
        return checks.size();
    }

    /** Replaces future automatic slots after a material shift-time edit. Already sent or
     * answered checks remain evidence and still count toward checksPerShift. */
    @Transactional
    public int rescheduleCurrentDateForShift(UUID tenantId, UUID shiftId, OffsetDateTime now) {
        List<Assignment> assignments = assignmentRepository
                .findByTenantIdAndShiftIdAndStatusAndDeletedAtIsNull(tenantId, shiftId, "active");
        int generated = 0;

        for (Assignment assignment : assignments) {
            Site site = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                    assignment.getSiteId(), tenantId).orElse(null);
            ZoneId zone = safeZone(site != null ? site.getTimezone() : VietnamTime.ID);
            LocalDate localDate = now.atZoneSameInstant(zone).toLocalDate();
            if (!assignmentAppliesOn(assignment, localDate)) {
                continue;
            }

            List<ScheduledCheck> pendingAutomatic = scheduledCheckRepository
                    .findByAssignmentAndDate(assignment.getId(), localDate).stream()
                    .filter(check -> check.getCheckIndex() > 0)
                    .filter(check -> "pending".equals(check.getStatus()))
                    .toList();
            for (ScheduledCheck check : pendingAutomatic) {
                check.setStatus("cancelled");
                check.setCancelledAt(now);
                check.setCancelledReason("shift_schedule_changed");
                dispatchQueue.cancel(check.getId());
            }
            if (!pendingAutomatic.isEmpty()) {
                scheduledCheckRepository.saveAll(pendingAutomatic);
            }

            generated += generateForAssignment(assignment, localDate, now, true);
        }
        log.info("Rescheduled current-date random checks for shiftId={} assignments={} created={}",
                shiftId, assignments.size(), generated);
        return generated;
    }

    private boolean assignmentAppliesOn(Assignment assignment, LocalDate date) {
        if (date.isBefore(assignment.getStartDate())
                || (assignment.getEndDate() != null && date.isAfter(assignment.getEndDate()))) {
            return false;
        }
        Short days = assignment.getDaysOfWeek();
        return days == null || (days & DayOfWeekBitmask.bitForDate(date)) != 0;
    }

    private boolean isReplaceableSystemCancellation(ScheduledCheck check) {
        if (!"cancelled".equals(check.getStatus())) {
            return false;
        }
        return "employee_not_in_active_session".equals(check.getCancelledReason())
                || "shift_schedule_changed".equals(check.getCancelledReason());
    }

    /**
     * Generates N check times within [windowStart, windowEnd] ensuring at least
     * minIntervalMinutes between any two checks.
     *
     * Uses the stars-and-bars technique: pick N values uniformly in the reduced
     * range [0, slack] where slack = windowMinutes - (N-1)*minInterval, sort them,
     * then add cumulative minimum-interval offsets. Guaranteed to satisfy the
     * spacing constraint as long as slack >= 0 (validated at config creation time).
     */
    List<OffsetDateTime> generateCheckTimes(OffsetDateTime windowStart, OffsetDateTime windowEnd,
                                            int checksPerShift, int minIntervalMinutes) {
        int windowMinutes = (int) java.time.Duration.between(windowStart, windowEnd).toMinutes();
        int slack = windowMinutes - (checksPerShift - 1) * minIntervalMinutes;

        if (slack < 0) {
            throw new IllegalArgumentException("Effective random-check window is too short");
        }

        int[] raw = new int[checksPerShift];
        for (int i = 0; i < checksPerShift; i++) {
            raw[i] = (slack > 0) ? random.nextInt(slack + 1) : 0;
        }
        Arrays.sort(raw);

        List<OffsetDateTime> times = new ArrayList<>(checksPerShift);
        for (int i = 0; i < checksPerShift; i++) {
            int offsetMinutes = raw[i] + (long) i * minIntervalMinutes > Integer.MAX_VALUE
                    ? windowMinutes - 1
                    : raw[i] + i * minIntervalMinutes;
            times.add(windowStart.plusMinutes(offsetMinutes));
        }
        return times;
    }

    /**
     * Intersects the config's configured window with the assignment's actual shift hours.
     * Returns empty if the two windows don't overlap at all (nothing to schedule).
     *
     * Both shift and custom windows may cross midnight. The returned values are absolute
     * instants in the site's timezone, so a generated check cannot wrap onto the wrong date.
     */
    Optional<EffectiveWindow> resolveEffectiveWindow(RandomCheckConfig config, Shift shift,
                                                     LocalDate date, ZoneId zone) {
        ZonedDateTime shiftStart = date.atTime(shift.getStartTime()).atZone(zone);
        ZonedDateTime shiftEnd = date.atTime(shift.getEndTime()).atZone(zone);
        if (shift.isAllowOvernight() || !shiftEnd.isAfter(shiftStart)) shiftEnd = shiftEnd.plusDays(1);

        if (!"custom_window".equals(config.getWindowMode())) {
            return Optional.of(new EffectiveWindow(
                    shiftStart.toOffsetDateTime(), shiftEnd.toOffsetDateTime()));
        }

        ZonedDateTime configStart = date.atTime(config.getAllowedStartTime()).atZone(zone);
        ZonedDateTime configEnd = date.atTime(config.getAllowedEndTime()).atZone(zone);
        if (!configEnd.isAfter(configStart)) configEnd = configEnd.plusDays(1);
        ZonedDateTime start = shiftStart.isAfter(configStart) ? shiftStart : configStart;
        ZonedDateTime end = shiftEnd.isBefore(configEnd) ? shiftEnd : configEnd;
        if (!start.isBefore(end)) {
            return Optional.empty();
        }
        return Optional.of(new EffectiveWindow(start.toOffsetDateTime(), end.toOffsetDateTime()));
    }

    record EffectiveWindow(OffsetDateTime start, OffsetDateTime end) {}

    /** @param effectiveCheckMode usually {@code c.getCheckMode()}, but callers pass a downgraded
     *  value (e.g. "location_only") when this specific check shouldn't require Face ID/liveness
     *  — see the enrollment fail-safe in generateForAssignment(). */
    private String buildSnapshot(RandomCheckConfig c, String effectiveCheckMode) {
        // Manual JSON construction avoids pulling in Jackson directly here
        return String.format(
                "{\"configId\":\"%s\",\"checkMode\":\"%s\",\"checksPerShift\":%d," +
                "\"minIntervalMinutes\":%d,\"allowedStartTime\":\"%s\"," +
                "\"allowedEndTime\":\"%s\",\"windowMode\":\"%s\",\"applicableRoles\":%s," +
                "\"responseWindowSeconds\":%d}",
                c.getId(), effectiveCheckMode, c.getChecksPerShift(),
                c.getMinIntervalMinutes(), c.getAllowedStartTime(), c.getAllowedEndTime(),
                c.getWindowMode(),
                buildRolesJson(c.getApplicableRoles()),
                c.getResponseWindowSeconds());
    }

    private String buildRolesJson(String rolesStr) {
        if (rolesStr == null || rolesStr.isBlank()) return "[]";
        String[] parts = rolesStr.split(",");
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(parts[i].trim()).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private ZoneId safeZone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            log.warn("Invalid timezone '{}', falling back to {}", tz, VietnamTime.ID);
            return VietnamTime.ZONE;
        }
    }
}
