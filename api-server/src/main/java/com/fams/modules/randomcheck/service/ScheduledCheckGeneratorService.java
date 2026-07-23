package com.fams.modules.randomcheck.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.assignment.util.DayOfWeekBitmask;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
    private final SecureRandom random = new SecureRandom();

    public ScheduledCheckGeneratorService(AssignmentRepository assignmentRepository,
                                          RandomCheckConfigRepository configRepository,
                                          ScheduledCheckRepository scheduledCheckRepository,
                                          ShiftRepository shiftRepository,
                                          SiteRepository siteRepository,
                                          RandomCheckDispatchQueue dispatchQueue,
                                          PlanLimitEnforcementService planLimitEnforcementService) {
        this.assignmentRepository = assignmentRepository;
        this.configRepository = configRepository;
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.shiftRepository = shiftRepository;
        this.siteRepository = siteRepository;
        this.dispatchQueue = dispatchQueue;
        this.planLimitEnforcementService = planLimitEnforcementService;
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
     * Generates scheduled check records for one assignment on a given date.
     * Skips silently if checks were already generated for this assignment+date.
     * Returns the number of ScheduledCheck records created.
     */
    @Transactional
    public int generateForAssignment(Assignment assignment, LocalDate date) {
        // Idempotency guard — skip if already generated
        if (scheduledCheckRepository.existsByAssignmentIdAndCheckDateAndDeletedAtIsNull(
                assignment.getId(), date)) {
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

        if (!config.isActive()) {
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
                .orElse("UTC");
        ZoneId zone = safeZone(timezone);

        // Enforce monthly random check quota
        int remaining = planLimitEnforcementService.getRemainingRandomChecks(assignment.getTenantId());
        if (remaining <= 0) {
            log.debug("Random check monthly quota exhausted for tenantId={} — skipping assignment={}",
                    assignment.getTenantId(), assignment.getId());
            return 0;
        }
        int checksToGenerate = Math.min(config.getChecksPerShift(), remaining);

        // Generate N random check times within the allowed window
        List<OffsetDateTime> checkTimes = generateCheckTimes(
                date, zone,
                config.getAllowedStartTime(), config.getAllowedEndTime(),
                checksToGenerate, config.getMinIntervalMinutes());

        String snapshot = buildSnapshot(config);

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
                    .checkIndex(i + 1)
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

    /**
     * Generates N check times within [windowStart, windowEnd] ensuring at least
     * minIntervalMinutes between any two checks.
     *
     * Uses the stars-and-bars technique: pick N values uniformly in the reduced
     * range [0, slack] where slack = windowMinutes - (N-1)*minInterval, sort them,
     * then add cumulative minimum-interval offsets. Guaranteed to satisfy the
     * spacing constraint as long as slack >= 0 (validated at config creation time).
     */
    List<OffsetDateTime> generateCheckTimes(LocalDate date, ZoneId zone,
                                            LocalTime windowStart, LocalTime windowEnd,
                                            int checksPerShift, int minIntervalMinutes) {
        int windowMinutes = (int) java.time.Duration.between(windowStart, windowEnd).toMinutes();
        int slack = windowMinutes - (checksPerShift - 1) * minIntervalMinutes;

        // Clamp slack to 0 if somehow negative (should not happen after task-93 validation)
        if (slack < 0) slack = 0;

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
            LocalTime checkLocalTime = windowStart.plusMinutes(offsetMinutes);
            OffsetDateTime odt = date.atTime(checkLocalTime)
                    .atZone(zone)
                    .toOffsetDateTime();
            times.add(odt);
        }
        return times;
    }

    private String buildSnapshot(RandomCheckConfig c) {
        // Manual JSON construction avoids pulling in Jackson directly here
        return String.format(
                "{\"configId\":\"%s\",\"checkMode\":\"%s\",\"checksPerShift\":%d," +
                "\"minIntervalMinutes\":%d,\"allowedStartTime\":\"%s\"," +
                "\"allowedEndTime\":\"%s\",\"applicableRoles\":%s," +
                "\"responseWindowSeconds\":%d}",
                c.getId(), c.getCheckMode(), c.getChecksPerShift(),
                c.getMinIntervalMinutes(), c.getAllowedStartTime(), c.getAllowedEndTime(),
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
            log.warn("Invalid timezone '{}', falling back to UTC", tz);
            return ZoneId.of("UTC");
        }
    }
}
