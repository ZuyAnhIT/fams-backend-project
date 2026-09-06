package com.fams.modules.attendance.service;

import com.fams.modules.attendance.dto.response.AttendanceHrMonthlyResponse;
import com.fams.modules.attendance.dto.response.AttendanceMonthlyResponse;
import com.fams.modules.attendance.dto.response.AttendanceSummaryResponse;
import com.fams.modules.attendance.entity.AttendanceSummary;
import com.fams.modules.attendance.repository.AttendanceMonthlyAggregateProjection;
import com.fams.modules.attendance.repository.AttendanceSummaryRepository;
import com.fams.modules.attendance.specification.AttendanceSummarySpecification;
import com.fams.modules.attendance.constant.AttendanceEventTypes;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.notification.service.NotificationService;
import com.fams.modules.randomcheck.repository.RandomCheckConfigRepository;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageImpl;

@Slf4j
@Service
public class AttendanceSummaryService {

    private final AttendanceSummaryRepository summaryRepository;
    private final CheckinRepository checkinRepository;
    private final SiteRepository siteRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRoleRepository userRoleRepository;
    private final SiteScopeService siteScopeService;
    private final AuditLogService auditLogService;
    private final ScheduledCheckRepository scheduledCheckRepository;
    private final RandomCheckConfigRepository randomCheckConfigRepository;
    private final NotificationService notificationService;

    public AttendanceSummaryService(AttendanceSummaryRepository summaryRepository,
                                    CheckinRepository checkinRepository,
                                    SiteRepository siteRepository,
                                    EmployeeRepository employeeRepository,
                                    UserRoleRepository userRoleRepository,
                                    SiteScopeService siteScopeService,
                                    AuditLogService auditLogService,
                                    ScheduledCheckRepository scheduledCheckRepository,
                                    RandomCheckConfigRepository randomCheckConfigRepository,
                                    NotificationService notificationService) {
        this.summaryRepository = summaryRepository;
        this.checkinRepository = checkinRepository;
        this.siteRepository = siteRepository;
        this.employeeRepository = employeeRepository;
        this.userRoleRepository = userRoleRepository;
        this.siteScopeService = siteScopeService;
        this.auditLogService = auditLogService;
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.randomCheckConfigRepository = randomCheckConfigRepository;
        this.notificationService = notificationService;
    }

    /** Tenant-default's failureEscalationThreshold, used to compute exceedsRandomCheckFailureThreshold
     *  on the HR monthly report. Deliberately uses the TENANT DEFAULT only (not each row's own site
     *  override) — resolving per-row would mean a per-site config lookup for every row in the page,
     *  and the escalation signal is informational, not a hard gate, so this simplification is fine.
     *  Falls back to "never exceeds" (Integer.MAX_VALUE) if the tenant has no default config at all. */
    private int resolveFailureEscalationThreshold(UUID tenantId) {
        return randomCheckConfigRepository.findTenantDefault(tenantId)
                .map(c -> c.getFailureEscalationThreshold())
                .orElse(Integer.MAX_VALUE);
    }

    /** Marker thrown internally by resolveSiteFilter to signal "no sites allowed at all" —
     *  caught by each caller to short-circuit into an empty result instead of querying. */
    private static final class NoSitesAllowed extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** Shared by listSummaries/listMonthlyAttendance: resolves the caller's allowed sites and
     *  reconciles them against an explicitly requested siteId filter (if any). Returns the
     *  siteId to actually query with (null = caller unrestricted, no specific-site filter). */
    private UUID resolveSiteFilter(UUID callerUserId, UUID tenantId, UUID requestedSiteId,
                                   boolean callerIsPlatformAdmin) {
        java.util.Optional<Set<UUID>> allowed =
                siteScopeService.resolveAllowedSiteIds(callerUserId, tenantId, callerIsPlatformAdmin);
        if (allowed.isEmpty()) {
            return requestedSiteId;
        }
        Set<UUID> allowedSites = allowed.get();
        if (allowedSites.isEmpty()) {
            throw new NoSitesAllowed();
        }
        if (requestedSiteId != null) {
            if (!allowedSites.contains(requestedSiteId)) {
                throw new AccessDeniedException("You do not have permission to view attendance for this site");
            }
            return requestedSiteId;
        }
        if (allowedSites.size() == 1) {
            return allowedSites.iterator().next();
        }
        throw new AccessDeniedException(
                "You are scoped to multiple sites — pass a specific siteId to view its attendance");
    }

    // ── Recompute triggers ──────────────────────────────────────────────────────

    /**
     * Called by CheckinService after a checkout is saved.
     * Determines the local attendance date from the check-in timestamp
     * and recomputes the summary for that employee/site/date.
     */
    @Transactional
    public void recomputeForCheckin(CheckinRecord record) {
        Site site = siteRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(record.getSiteId(), record.getTenantId())
                .orElse(null);
        String timezone = site != null ? site.getTimezone() : "UTC";

        LocalDate attendanceDate = record.getCheckInAt()
                .atZoneSameInstant(ZoneId.of(timezone))
                .toLocalDate();

        recompute(record.getTenantId(), record.getEmployeeId(), record.getSiteId(),
                attendanceDate, timezone, record.getShiftId(), record.getAssignmentId());
    }

    /**
     * Called by ViolationService right after HR confirms or dismisses a violation (found via
     * audit 2026-08-02: resolving a violation previously never touched AttendanceSummary at all,
     * so {@code hasRandomCheckFailure} — see {@link com.fams.modules.randomcheck.repository
     * .ScheduledCheckRepository#existsFailedOrNoResponseCheck} — stayed stuck at whatever it was
     * computed as before HR's decision, even after a dismissal proved the failure was a false
     * positive). No-op if no summary row exists yet for that employee/site/date — same early-
     * return semantics as {@link #recompute}: there's nothing to refresh until the day actually
     * has check-in activity, and this call must never CREATE a summary row on its own.
     */
    @Transactional
    public void recomputeIfSummaryExists(UUID tenantId, UUID employeeId, UUID siteId, LocalDate date) {
        summaryRepository.findByTenantIdAndEmployeeIdAndSiteIdAndAttendanceDateAndDeletedAtIsNull(
                tenantId, employeeId, siteId, date
        ).ifPresent(existing -> {
            String timezone = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                    .map(Site::getTimezone)
                    .orElse("UTC");
            recompute(tenantId, employeeId, siteId, date, timezone,
                    existing.getShiftId(), existing.getAssignmentId());
        });
    }

    /**
     * Nightly catch-up: recomputes summaries for EVERY tenant's check-in records whose local
     * attendance date equals {@code targetDate}. Called only by AttendanceSummaryJob — a system
     * batch job, so tenant-agnostic is correct here. User-facing/API-triggered recompute must go
     * through {@link #triggerRecompute} instead, which is properly tenant/site-scoped.
     */
    @Transactional
    public void recomputeForDate(LocalDate targetDate) {
        OffsetDateTime from = targetDate.minusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to   = targetDate.plusDays(2).atStartOfDay().atOffset(ZoneOffset.UTC);
        List<CheckinRecord> candidates = checkinRepository.findCheckinsBetween(from, to);
        recomputeGroups(candidates, targetDate);
    }

    /**
     * Platform-admin-only backfill for a date range, across every tenant — reuses the exact same
     * recompute() logic as every other path (not a separate SQL formula), so results are
     * guaranteed consistent with real-time recompute. Needed after V79 (2026-07-31): that
     * migration added has_pending_review_session/has_rejected_session with a default of false
     * and did NOT retroactively fix totalWorkMinutes/late/early/OT on rows computed under the
     * OLD (unfiltered) logic — those historical rows kept counting pending_review/rejected
     * sessions until something re-triggers their recompute. This is that trigger, run explicitly
     * and deliberately rather than silently. NOT transactional as a whole — each date commits
     * independently via recomputeForDate(LocalDate), so one bad day can't roll back the rest.
     * Skips (never touches) any summary with a non-null adjustmentReason, same as every other
     * recompute path — an HR-adjusted row's numbers are never silently rewritten.
     */
    public BackfillResult backfillDateRange(LocalDate from, LocalDate to) {
        int daysProcessed = 0;
        int daysFailed = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            try {
                recomputeForDate(date);
                daysProcessed++;
            } catch (Exception e) {
                daysFailed++;
                log.error("Backfill failed for date={}: {}", date, e.getMessage(), e);
            }
        }
        log.info("Attendance backfill complete: from={} to={} daysProcessed={} daysFailed={}",
                from, to, daysProcessed, daysFailed);
        return new BackfillResult(daysProcessed, daysFailed);
    }

    public record BackfillResult(int daysProcessed, int daysFailed) {
    }

    /**
     * HR/Admin-facing entry point for POST .../attendance/recompute — properly scoped to the
     * calling tenant (and, for a site-restricted caller, to their allowed site(s)). Found via
     * audit (2026-07-31): the endpoint previously called the tenant-agnostic recomputeForDate(
     * LocalDate) above directly, so any HR/Admin with attendance:adjust
     * in ANY tenant could trigger a recompute that silently touched every OTHER tenant's
     * attendance data for that date — a real cross-tenant data-isolation violation, not just a
     * permission-check gap, since recompute() itself had no tenant filter in its query.
     */
    @Transactional
    public void triggerRecompute(UUID tenantId, UUID requestedSiteId, LocalDate targetDate,
                                  UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("attendance:adjust")) {
                throw new AccessDeniedException("You do not have permission to trigger attendance recompute");
            }
        }

        UUID effectiveSiteId;
        try {
            effectiveSiteId = resolveSiteFilter(callerUserId, tenantId, requestedSiteId, callerIsPlatformAdmin);
        } catch (NoSitesAllowed e) {
            log.info("Recompute no-op: caller has no allowed sites tenantId={} date={}", tenantId, targetDate);
            return;
        }

        OffsetDateTime from = targetDate.minusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to   = targetDate.plusDays(2).atStartOfDay().atOffset(ZoneOffset.UTC);
        List<CheckinRecord> candidates = checkinRepository
                .findCheckinsBetweenForTenant(tenantId, effectiveSiteId, from, to);
        recomputeGroups(candidates, targetDate);
    }

    /** Shared by recomputeForDate (all-tenant, job-only) and triggerRecompute (tenant-scoped,
     *  API-facing) — groups candidate sessions by tenant+employee+site and recomputes each group
     *  whose LOCAL (site-timezone) check-in date actually equals targetDate. */
    private void recomputeGroups(List<CheckinRecord> candidates, LocalDate targetDate) {
        if (candidates.isEmpty()) {
            log.info("No check-ins found for target date {}", targetDate);
            return;
        }

        // Group by tenant+employee+site; take one representative per group to look up timezone.
        Map<String, CheckinRecord> repByGroup = new LinkedHashMap<>();
        for (CheckinRecord c : candidates) {
            String key = c.getTenantId() + ":" + c.getEmployeeId() + ":" + c.getSiteId();
            repByGroup.putIfAbsent(key, c);
        }

        for (Map.Entry<String, CheckinRecord> entry : repByGroup.entrySet()) {
            CheckinRecord rep = entry.getValue();
            try {
                Site site = siteRepository
                        .findByIdAndTenantIdAndDeletedAtIsNull(rep.getSiteId(), rep.getTenantId())
                        .orElse(null);
                String timezone = site != null ? site.getTimezone() : "UTC";
                ZoneId zone = ZoneId.of(timezone);

                boolean anyMatchTargetDate = candidates.stream()
                        .filter(c -> c.getTenantId().equals(rep.getTenantId())
                                && c.getEmployeeId().equals(rep.getEmployeeId())
                                && c.getSiteId().equals(rep.getSiteId()))
                        .map(c -> c.getCheckInAt().atZoneSameInstant(zone).toLocalDate())
                        .anyMatch(targetDate::equals);

                if (anyMatchTargetDate) {
                    recompute(rep.getTenantId(), rep.getEmployeeId(), rep.getSiteId(),
                            targetDate, timezone, rep.getShiftId(), rep.getAssignmentId());
                }
            } catch (Exception e) {
                log.warn("Failed to recompute summary for group {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /**
     * Core upsert: aggregates all check-in sessions for the given employee/site/date
     * and saves (creates or updates) the attendance_summary row. Idempotent.
     */
    @Transactional
    public void recompute(UUID tenantId, UUID employeeId, UUID siteId,
                          LocalDate date, String timezone, UUID shiftId, UUID assignmentId) {
        ZoneId zone = ZoneId.of(timezone);
        OffsetDateTime startOfDay = date.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime endOfDay   = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        List<CheckinRecord> allSessions = checkinRepository
                .findSessionsForDateRange(tenantId, employeeId, siteId, startOfDay, endOfDay);

        // Nothing ever recorded for this employee/site/date — no summary row needed at all
        // (distinct from "sessions exist but all excluded below", handled after filtering).
        if (allSessions.isEmpty()) {
            return;
        }

        // Only 'valid' sessions count toward every computed field below (work minutes, late,
        // early-leave, OT, missing-checkout). A 'pending_review' session (unconfirmed — GPS/face
        // escalation) or 'rejected' session (HR-confirmed invalid, e.g. buddy check-in) must NOT
        // affect pay-relevant numbers — this directly matches the promise already made to the
        // employee at checkout time (CheckinService: "This won't affect your pay until
        // reviewed"). Found via audit (2026-07-31, see docs/api/attendance-management-api.md) —
        // previously ALL sessions counted regardless of status, contradicting that promise.
        boolean hasPendingReviewSession = allSessions.stream()
                .anyMatch(c -> "pending_review".equals(c.getStatus()));
        boolean hasRejectedSession = allSessions.stream()
                .anyMatch(c -> "rejected".equals(c.getStatus()));
        List<CheckinRecord> sessions = allSessions.stream()
                .filter(c -> "valid".equals(c.getStatus()))
                .collect(Collectors.toList());

        int totalWorkMinutes = sessions.stream()
                .mapToInt(c -> c.getWorkMinutes() != null ? c.getWorkMinutes() : 0)
                .sum();

        OffsetDateTime firstCheckinAt = sessions.stream()
                .map(CheckinRecord::getCheckInAt)
                .filter(Objects::nonNull)
                .min(OffsetDateTime::compareTo)
                .orElse(null);

        OffsetDateTime lastCheckoutAt = sessions.stream()
                .map(CheckinRecord::getCheckOutAt)
                .filter(Objects::nonNull)
                .max(OffsetDateTime::compareTo)
                .orElse(null);

        boolean hasOpenSession = sessions.stream().anyMatch(c -> c.getCheckOutAt() == null);
        String status = hasOpenSession ? "incomplete" : "present";

        // Tasks 81-83: late, early-leave, and OT detection all require the shift's time-affecting
        // fields — sourced from each session's OWN snapshot (captured at check-in time), never a
        // live Shift re-fetch, so a Shift edited after these sessions occurred can never change
        // an already-computed day's late/early/OT figures (payroll/audit correctness).
        boolean isLate = false;
        int lateMinutes = 0;
        boolean isEarlyLeave = false;
        int earlyLeaveMinutes = 0;
        int otMinutes = 0;

        CheckinRecord shiftSnapshotSource = sessions.stream()
                .filter(s -> s.getShiftStartTime() != null && s.getShiftEndTime() != null)
                .findFirst().orElse(null);

        if (shiftSnapshotSource != null) {
            LocalTime startTime = shiftSnapshotSource.getShiftStartTime();
            LocalTime endTime = shiftSnapshotSource.getShiftEndTime();
            boolean allowOvernight = Boolean.TRUE.equals(shiftSnapshotSource.getShiftAllowOvernight());
            boolean allowOvertime = Boolean.TRUE.equals(shiftSnapshotSource.getShiftAllowOvertime());
            int lateCheckoutMinutes = shiftSnapshotSource.getShiftLateCheckoutMinutes() != null
                    ? shiftSnapshotSource.getShiftLateCheckoutMinutes() : 0;
            int graceMinutes = shiftSnapshotSource.getShiftGraceMinutes() != null
                    ? shiftSnapshotSource.getShiftGraceMinutes() : 0;

            // For overnight shifts the shift end is on the next calendar day.
            LocalDate endDate = allowOvernight ? date.plusDays(1) : date;
            ZonedDateTime shiftStart = ZonedDateTime.of(date, startTime, zone);
            ZonedDateTime shiftEnd   = ZonedDateTime.of(endDate, endTime, zone);

            // Task 81: late detection — first check-in vs shift start, tolerant of graceMinutes.
            // Arriving within grace still records lateMinutes=0; arriving beyond it records the
            // FULL raw delay (not delay-minus-grace) — grace only decides whether isLate trips.
            if (firstCheckinAt != null) {
                ZonedDateTime actualStart = firstCheckinAt.atZoneSameInstant(zone);
                if (actualStart.isAfter(shiftStart)) {
                    int rawDelayMinutes = (int) Duration.between(shiftStart, actualStart).toMinutes();
                    if (rawDelayMinutes > graceMinutes) {
                        lateMinutes = rawDelayMinutes;
                        isLate = true;
                    }
                }
            }

            // Task 82: early-leave detection — last check-out vs shift end (only when all sessions complete)
            if (lastCheckoutAt != null && !hasOpenSession) {
                ZonedDateTime actualEnd = lastCheckoutAt.atZoneSameInstant(zone);
                if (actualEnd.isBefore(shiftEnd)) {
                    earlyLeaveMinutes = (int) Duration.between(actualEnd, shiftEnd).toMinutes();
                    isEarlyLeave = earlyLeaveMinutes > 0;
                }
            }

            // Task 83: OT — minutes worked beyond shift end, capped per session by lateCheckoutMinutes.
            // Only counted when the shift explicitly allows overtime.
            if (allowOvertime) {
                ZonedDateTime otCap = shiftEnd.plusMinutes(lateCheckoutMinutes);
                for (CheckinRecord session : sessions) {
                    if (session.getCheckOutAt() == null) continue;
                    ZonedDateTime checkOut = session.getCheckOutAt().atZoneSameInstant(zone);
                    if (checkOut.isAfter(shiftEnd)) {
                        ZonedDateTime effectiveEnd = checkOut.isAfter(otCap) ? otCap : checkOut;
                        otMinutes += (int) Duration.between(shiftEnd, effectiveEnd).toMinutes();
                    }
                }
            }
        }

        // #60 (docs/api/backend-feature-audit-2026-08-07.md): warn-only OT hour limits. Uses the
        // SAME session snapshot as Task 83 above (never a live Shift re-fetch — a limit edited
        // later must not retroactively flag/unflag an already-computed day, same invariant as
        // every other shift_* snapshot field). null limit = unlimited, never flags.
        boolean otDailyLimitExceeded = false;
        boolean otWeeklyLimitExceeded = false;
        if (shiftSnapshotSource != null) {
            Integer maxOtPerDay = shiftSnapshotSource.getShiftMaxOtMinutesPerDay();
            if (maxOtPerDay != null && otMinutes > maxOtPerDay) {
                otDailyLimitExceeded = true;
            }

            Integer maxOtPerWeek = shiftSnapshotSource.getShiftMaxOtMinutesPerWeek();
            if (maxOtPerWeek != null) {
                // ISO week (Mon-Sun), same default most WFM systems (Deputy/ADP) use for a
                // weekly OT cutoff. Employee-scoped, not site-scoped — a person's weekly OT
                // exposure doesn't reset just because they moved sites mid-week.
                LocalDate weekStart = date.with(java.time.DayOfWeek.MONDAY);
                LocalDate weekEndExclusive = weekStart.plusDays(7);
                int weeklyOtMinutes = otMinutes; // today's freshly computed value, not yet persisted
                for (AttendanceSummary row : summaryRepository.findByTenantIdAndEmployeeIdAndDateRange(
                        tenantId, employeeId, weekStart, weekEndExclusive)) {
                    if (!row.getAttendanceDate().equals(date)) {
                        weeklyOtMinutes += row.getOtMinutes();
                    }
                }
                otWeeklyLimitExceeded = weeklyOtMinutes > maxOtPerWeek;
            }
        }

        // Task 84: missing_checkout — only flagged for past days with open sessions.
        // A session open on today's date is still in progress, not yet "missing".
        boolean missingCheckout = hasOpenSession && date.isBefore(LocalDate.now(zone));

        AttendanceSummary existing = summaryRepository
                .findByTenantIdAndEmployeeIdAndSiteIdAndAttendanceDateAndDeletedAtIsNull(
                        tenantId, employeeId, siteId, date)
                .orElse(null);

        // HR has manually adjusted this day (POST .../adjust) — do not let an automatic
        // recompute trigger (a late offline-sync upload landing on this date, the nightly job
        // re-touching it, or another event) silently overwrite their decision with no warning.
        // HR must explicitly re-adjust (or clear adjustmentReason) to accept new source data.
        // Found via audit (2026-07-31): this previously had no protection at all.
        if (existing != null && existing.getAdjustmentReason() != null) {
            log.info("Skipping auto-recompute for HR-adjusted summary: tenantId={} employeeId={} "
                            + "siteId={} date={} reason={}",
                    tenantId, employeeId, siteId, date, existing.getAdjustmentReason());
            return;
        }

        AttendanceSummary summary = existing != null ? existing : AttendanceSummary.builder()
                .tenantId(tenantId)
                .employeeId(employeeId)
                .siteId(siteId)
                .shiftId(shiftId)
                .assignmentId(assignmentId)
                .attendanceDate(date)
                .build();

        summary.setFirstCheckinAt(firstCheckinAt);
        summary.setLastCheckoutAt(lastCheckoutAt);
        summary.setTotalWorkMinutes(totalWorkMinutes);
        summary.setSessionCount(sessions.size());
        summary.setStatus(status);
        summary.setLate(isLate);
        summary.setLateMinutes(lateMinutes);
        summary.setEarlyLeave(isEarlyLeave);
        summary.setEarlyLeaveMinutes(earlyLeaveMinutes);
        summary.setOtMinutes(otMinutes);
        summary.setOtDailyLimitExceeded(otDailyLimitExceeded);
        summary.setOtWeeklyLimitExceeded(otWeeklyLimitExceeded);
        summary.setMissingCheckout(missingCheckout);
        summary.setHasPendingReviewSession(hasPendingReviewSession);
        summary.setHasRejectedSession(hasRejectedSession);
        summary.setHasRandomCheckFailure(
                scheduledCheckRepository.existsFailedOrNoResponseCheck(tenantId, employeeId, siteId, date));

        summaryRepository.save(summary);
        log.info("Attendance summary upserted: tenantId={} employeeId={} siteId={} date={} status={} " +
                "workMinutes={} isLate={} lateMinutes={} isEarlyLeave={} earlyLeaveMinutes={} " +
                "otMinutes={} missingCheckout={}",
                tenantId, employeeId, siteId, date, status, totalWorkMinutes,
                isLate, lateMinutes, isEarlyLeave, earlyLeaveMinutes, otMinutes, missingCheckout);

        // #84 (2026-08-17): notify on the false→true transition only — `existing` was fetched
        // BEFORE this save, so it still reflects the pre-recompute state. Guarding on the
        // transition (not just "missingCheckout == true") avoids re-notifying on every later
        // recompute of the same already-flagged day (e.g. an unrelated field changing).
        boolean wasMissingCheckout = existing != null && existing.isMissingCheckout();
        if (missingCheckout && !wasMissingCheckout) {
            notifyMissingCheckout(tenantId, employeeId, siteId, date);
        }
    }

    private void notifyMissingCheckout(UUID tenantId, UUID employeeId, UUID siteId, LocalDate date) {
        try {
            Employee employee = employeeRepository.findById(employeeId).orElse(null);
            String empName = resolveEmployeeName(tenantId, employeeId);
            String siteName = resolveSiteName(tenantId, siteId);
            String body = String.format("%s chưa check-out cho ngày %s tại %s.", empName, date, siteName);
            Map<String, Object> metadata = Map.of(
                    "employeeId", employeeId.toString(),
                    "siteId", siteId.toString(),
                    "attendanceDate", date.toString());

            if (employee != null && employee.getUserId() != null) {
                notificationService.createNotification(
                        tenantId, employee.getUserId(),
                        AttendanceEventTypes.MISSING_CHECKOUT_EMPLOYEE,
                        "Quên check-out",
                        body, metadata);
            }

            Set<UUID> hrUserIds = userRoleRepository
                    .findDistinctActiveHolderIdsOfPermissionInTenant(tenantId, "attendance:list");
            for (UUID hrUserId : hrUserIds) {
                if (employee != null && hrUserId.equals(employee.getUserId())) continue;
                notificationService.createNotification(
                        tenantId, hrUserId,
                        AttendanceEventTypes.MISSING_CHECKOUT_HR,
                        "Nhân viên quên check-out",
                        body, metadata);
            }
        } catch (Exception e) {
            log.warn("Failed to send missing-checkout notification tenantId={} employeeId={} date={}: {}",
                    tenantId, employeeId, date, e.getMessage());
        }
    }

    // ── Read APIs ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<AttendanceSummaryResponse> listSummaries(UUID tenantId,
                                                                  UUID employeeId, UUID siteId,
                                                                  String status,
                                                                  LocalDate from, LocalDate to,
                                                                  int page, int size,
                                                                  UUID callerUserId,
                                                                  boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("attendance:list")) {
                throw new AccessDeniedException("You do not have permission to list attendance summaries");
            }
        }

        UUID effectiveSiteFilter;
        try {
            effectiveSiteFilter = resolveSiteFilter(callerUserId, tenantId, siteId, callerIsPlatformAdmin);
        } catch (NoSitesAllowed e) {
            return PageResponse.from(Page.empty(PageRequest.of(page, size)));
        }

        Specification<AttendanceSummary> spec =
                AttendanceSummarySpecification.build(tenantId, employeeId, effectiveSiteFilter, status, from, to);
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "attendanceDate"));

        Page<AttendanceSummary> rawPage = summaryRepository.findAll(spec, pageable);

        Set<UUID> empIds  = rawPage.stream().map(AttendanceSummary::getEmployeeId).collect(Collectors.toSet());
        Set<UUID> siteIds = rawPage.stream().map(AttendanceSummary::getSiteId).collect(Collectors.toSet());
        Map<UUID, String> empNames  = buildEmployeeNameMap(tenantId, empIds);
        Map<UUID, String> siteNames = buildSiteNameMap(tenantId, siteIds);

        Page<AttendanceSummaryResponse> resultPage = rawPage.map(a ->
                toResponse(a, empNames.get(a.getEmployeeId()), siteNames.get(a.getSiteId())));

        log.info("HR attendance list: tenantId={} employeeId={} siteId={} total={}",
                tenantId, employeeId, siteId, resultPage.getTotalElements());

        return PageResponse.from(resultPage);
    }

    @Transactional(readOnly = true)
    public AttendanceSummaryResponse getSummary(UUID tenantId, UUID summaryId,
                                                 UUID callerUserId, boolean callerIsPlatformAdmin) {
        boolean canListTenantAttendance = callerIsPlatformAdmin;
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("attendance:read")) {
                throw new AccessDeniedException("You do not have permission to view this attendance summary");
            }
            canListTenantAttendance = perms.contains("attendance:list");
        }

        AttendanceSummary summary = summaryRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(summaryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance summary not found: " + summaryId));

        if (!canListTenantAttendance) {
            Employee callerEmployee = employeeRepository
                    .findByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId)
                    .orElseThrow(() -> new AccessDeniedException(
                            "You may only view your own attendance summary"));
            if (!summary.getEmployeeId().equals(callerEmployee.getId())) {
                throw new AccessDeniedException("You may only view your own attendance summary");
            }
        } else if (!siteScopeService.isSiteAllowed(
                callerUserId, tenantId, summary.getSiteId(), callerIsPlatformAdmin)) {
            throw new AccessDeniedException("You do not have permission to view this attendance summary");
        }

        String empName  = resolveEmployeeName(tenantId, summary.getEmployeeId());
        String siteName = resolveSiteName(tenantId, summary.getSiteId());
        return toResponse(summary, empName, siteName);
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceSummaryResponse> getMyAttendance(UUID tenantId, UUID callerUserId,
                                                                    UUID siteId,
                                                                    LocalDate from, LocalDate to,
                                                                    int page, int size) {
        Employee employee = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No employee profile found for this user in tenant: " + tenantId));

        Specification<AttendanceSummary> spec =
                AttendanceSummarySpecification.build(tenantId, employee.getId(), siteId, null, from, to);
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "attendanceDate"));

        String employeeName = (employee.getFirstName() + " " + employee.getLastName()).trim();

        Page<AttendanceSummary> rawPage = summaryRepository.findAll(spec, pageable);

        Set<UUID> siteIds = rawPage.stream().map(AttendanceSummary::getSiteId).collect(Collectors.toSet());
        Map<UUID, String> siteNames = buildSiteNameMap(tenantId, siteIds);

        Page<AttendanceSummaryResponse> resultPage = rawPage.map(a ->
                toResponse(a, employeeName, siteNames.get(a.getSiteId())));

        log.info("Employee attendance: tenantId={} employeeId={} total={}",
                tenantId, employee.getId(), resultPage.getTotalElements());

        return PageResponse.from(resultPage);
    }

    @Transactional(readOnly = true)
    public AttendanceMonthlyResponse getMyMonthlyAttendance(UUID tenantId, UUID callerUserId,
                                                             UUID siteId,
                                                             int year, int month) {
        Employee employee = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No employee profile found for this user in tenant: " + tenantId));

        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to   = from.plusMonths(1);

        List<AttendanceSummary> rows = summaryRepository
                .findByTenantIdAndEmployeeIdAndSiteIdAndDateRange(tenantId, employee.getId(), siteId, from, to);

        int presentDays          = rows.size();
        int totalWorkMinutes     = rows.stream().mapToInt(AttendanceSummary::getTotalWorkMinutes).sum();
        int lateDays             = (int) rows.stream().filter(AttendanceSummary::isLate).count();
        int totalLateMinutes     = rows.stream().mapToInt(AttendanceSummary::getLateMinutes).sum();
        int earlyLeaveDays       = (int) rows.stream().filter(AttendanceSummary::isEarlyLeave).count();
        int totalEarlyLeaveMinutes = rows.stream().mapToInt(AttendanceSummary::getEarlyLeaveMinutes).sum();
        int totalOtMinutes       = rows.stream().mapToInt(AttendanceSummary::getOtMinutes).sum();
        int missingCheckoutDays  = (int) rows.stream().filter(AttendanceSummary::isMissingCheckout).count();
        int daysWithPendingReview = (int) rows.stream().filter(AttendanceSummary::isHasPendingReviewSession).count();
        int daysWithRejectedSession = (int) rows.stream().filter(AttendanceSummary::isHasRejectedSession).count();
        int daysWithRandomCheckFailure = (int) rows.stream().filter(AttendanceSummary::isHasRandomCheckFailure).count();

        String employeeName = (employee.getFirstName() + " " + employee.getLastName()).trim();
        Set<UUID> siteIds = rows.stream().map(AttendanceSummary::getSiteId).collect(Collectors.toSet());
        Map<UUID, String> siteNames = buildSiteNameMap(tenantId, siteIds);
        List<AttendanceSummaryResponse> daily = rows.stream()
                .map(a -> toResponse(a, employeeName, siteNames.get(a.getSiteId())))
                .collect(Collectors.toList());

        log.info("Monthly attendance: tenantId={} employeeId={} {}/{} presentDays={}",
                tenantId, employee.getId(), year, month, presentDays);

        return AttendanceMonthlyResponse.builder()
                .tenantId(tenantId)
                .employeeId(employee.getId())
                .year(year)
                .month(month)
                .presentDays(presentDays)
                .totalWorkMinutes(totalWorkMinutes)
                .lateDays(lateDays)
                .totalLateMinutes(totalLateMinutes)
                .earlyLeaveDays(earlyLeaveDays)
                .totalEarlyLeaveMinutes(totalEarlyLeaveMinutes)
                .totalOtMinutes(totalOtMinutes)
                .missingCheckoutDays(missingCheckoutDays)
                .daysWithPendingReview(daysWithPendingReview)
                .daysWithRejectedSession(daysWithRejectedSession)
                .daysWithRandomCheckFailure(daysWithRandomCheckFailure)
                .dailySummaries(daily)
                .build();
    }

    /** #86 (2026-08-17): whitelist of columns HR can sort the monthly aggregate by — matches the
     *  CASE branches in {@link AttendanceSummaryRepository#aggregateMonthly}. Anything else is
     *  rejected rather than silently ignored, so a typo doesn't quietly fall back to default
     *  order and confuse HR into thinking the sort just "isn't working". */
    private static final Set<String> MONTHLY_SORTABLE_FIELDS = Set.of(
            "totalWorkMinutes", "totalLateMinutes", "totalOtMinutes", "missingCheckoutDays", "presentDays");

    /** #86: whitelist of daily statuses HR can filter the monthly aggregate by — matches
     *  {@code AttendanceSummary.status} (see recompute()). */
    private static final Set<String> MONTHLY_FILTERABLE_STATUSES = Set.of("present", "incomplete");

    @Transactional(readOnly = true)
    public PageResponse<AttendanceHrMonthlyResponse> listMonthlyAttendance(
            UUID tenantId, UUID employeeId, UUID siteId, String status, String sortBy, String sortDir,
            int year, int month, int page, int size, UUID callerUserId, boolean callerIsPlatformAdmin) {

        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("attendance:list")) {
                throw new AccessDeniedException("You do not have permission to list attendance summaries");
            }
        }

        if (status != null && !MONTHLY_FILTERABLE_STATUSES.contains(status)) {
            throw new IllegalArgumentException("status must be one of " + MONTHLY_FILTERABLE_STATUSES);
        }
        if (sortBy != null && !MONTHLY_SORTABLE_FIELDS.contains(sortBy)) {
            throw new IllegalArgumentException("sortBy must be one of " + MONTHLY_SORTABLE_FIELDS);
        }
        String effectiveSortDir = "desc".equalsIgnoreCase(sortDir) ? "desc" : "asc";

        UUID effectiveSiteFilter;
        try {
            effectiveSiteFilter = resolveSiteFilter(callerUserId, tenantId, siteId, callerIsPlatformAdmin);
        } catch (NoSitesAllowed e) {
            return PageResponse.from(new PageImpl<>(List.of(), PageRequest.of(page, size), 0));
        }

        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to   = from.plusMonths(1);

        // Grouped and paginated at the DB level (aggregateMonthly) instead of loading every
        // daily row for the whole tenant/month into Java and grouping/paging in memory — a real
        // scaling concern found in an audit (2026-07-31), see docs/api/attendance-management-api.md.
        PageRequest pageable = PageRequest.of(page, size);
        Page<AttendanceMonthlyAggregateProjection> rawPage = summaryRepository.aggregateMonthly(
                tenantId, employeeId, effectiveSiteFilter, from, to, status, sortBy, effectiveSortDir, pageable);

        Set<UUID> empIds  = rawPage.stream().map(AttendanceMonthlyAggregateProjection::getEmployeeId).collect(Collectors.toSet());
        Set<UUID> siteIds = rawPage.stream().map(AttendanceMonthlyAggregateProjection::getSiteId).collect(Collectors.toSet());
        Map<UUID, String> empNames  = buildEmployeeNameMap(tenantId, empIds);
        Map<UUID, String> siteNames = buildSiteNameMap(tenantId, siteIds);
        int failureThreshold = resolveFailureEscalationThreshold(tenantId);

        Page<AttendanceHrMonthlyResponse> resultPage = rawPage.map(row -> {
            int daysWithRandomCheckFailure = row.getDaysWithRandomCheckFailure().intValue();
            return AttendanceHrMonthlyResponse.builder()
                        .tenantId(tenantId)
                        .employeeId(row.getEmployeeId())
                        .employeeName(empNames.get(row.getEmployeeId()))
                        .siteId(row.getSiteId())
                        .siteName(siteNames.get(row.getSiteId()))
                        .year(year)
                        .month(month)
                        .presentDays(row.getPresentDays().intValue())
                        .totalWorkMinutes(row.getTotalWorkMinutes().intValue())
                        .lateDays(row.getLateDays().intValue())
                        .totalLateMinutes(row.getTotalLateMinutes().intValue())
                        .earlyLeaveDays(row.getEarlyLeaveDays().intValue())
                        .totalEarlyLeaveMinutes(row.getTotalEarlyLeaveMinutes().intValue())
                        .totalOtMinutes(row.getTotalOtMinutes().intValue())
                        .missingCheckoutDays(row.getMissingCheckoutDays().intValue())
                        .daysWithPendingReview(row.getDaysWithPendingReview().intValue())
                        .daysWithRejectedSession(row.getDaysWithRejectedSession().intValue())
                        .daysWithRandomCheckFailure(daysWithRandomCheckFailure)
                        .exceedsRandomCheckFailureThreshold(daysWithRandomCheckFailure >= failureThreshold)
                        .build();
        });

        log.info("HR monthly list: tenantId={} employeeId={} siteId={} {}/{} groups={}",
                tenantId, employeeId, siteId, year, month, resultPage.getTotalElements());

        return PageResponse.from(resultPage);
    }

    // ── Task 112: HR adjust attendance summary ────────────────────────────────

    @Transactional
    public AttendanceSummaryResponse adjustSummary(UUID tenantId, UUID summaryId,
                                                    com.fams.modules.attendance.dto.request.AdjustAttendanceSummaryRequest request,
                                                    UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("attendance:adjust")) {
                throw new AccessDeniedException("You do not have permission to adjust attendance summaries");
            }
        }

        AttendanceSummary summary = summaryRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(summaryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance summary not found: " + summaryId));

        if (!siteScopeService.isSiteAllowed(callerUserId, tenantId, summary.getSiteId(), callerIsPlatformAdmin)) {
            throw new AccessDeniedException("You do not have permission to adjust attendance for this site");
        }

        Map<String, Object> oldValue = new LinkedHashMap<>();
        oldValue.put("totalWorkMinutes", summary.getTotalWorkMinutes());
        oldValue.put("status", summary.getStatus());
        oldValue.put("late", summary.isLate());
        oldValue.put("lateMinutes", summary.getLateMinutes());
        oldValue.put("earlyLeave", summary.isEarlyLeave());
        oldValue.put("earlyLeaveMinutes", summary.getEarlyLeaveMinutes());
        oldValue.put("otMinutes", summary.getOtMinutes());
        oldValue.put("missingCheckout", summary.isMissingCheckout());

        if (request.getTotalWorkMinutes() != null) summary.setTotalWorkMinutes(request.getTotalWorkMinutes());
        if (request.getStatus() != null)           summary.setStatus(request.getStatus());
        if (request.getLate() != null)             summary.setLate(request.getLate());
        if (request.getLateMinutes() != null)      summary.setLateMinutes(request.getLateMinutes());
        if (request.getEarlyLeave() != null)       summary.setEarlyLeave(request.getEarlyLeave());
        if (request.getEarlyLeaveMinutes() != null) summary.setEarlyLeaveMinutes(request.getEarlyLeaveMinutes());
        if (request.getOtMinutes() != null)        summary.setOtMinutes(request.getOtMinutes());
        if (request.getMissingCheckout() != null)  summary.setMissingCheckout(request.getMissingCheckout());
        summary.setAdjustmentReason(request.getReason());

        summaryRepository.save(summary);

        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("totalWorkMinutes", summary.getTotalWorkMinutes());
        newValue.put("status", summary.getStatus());
        newValue.put("late", summary.isLate());
        newValue.put("lateMinutes", summary.getLateMinutes());
        newValue.put("earlyLeave", summary.isEarlyLeave());
        newValue.put("earlyLeaveMinutes", summary.getEarlyLeaveMinutes());
        newValue.put("otMinutes", summary.getOtMinutes());
        newValue.put("missingCheckout", summary.isMissingCheckout());
        newValue.put("reason", request.getReason());

        try {
            auditLogService.record(
                    tenantId, callerUserId, null,
                    "AttendanceSummary", summaryId.toString(), "attendance_summary_adjusted",
                    oldValue, newValue,
                    com.fams.shared.security.HttpRequestUtils.currentRequestId(), null, null);
        } catch (Exception e) {
            log.warn("Failed to record audit log for attendance_summary_adjusted summaryId={}: {}",
                    summaryId, e.getMessage());
        }

        log.info("HR adjusted attendance summary: summaryId={} by={} reason={}",
                summaryId, callerUserId, request.getReason());

        String empName  = resolveEmployeeName(tenantId, summary.getEmployeeId());
        String siteName = resolveSiteName(tenantId, summary.getSiteId());
        return toResponse(summary, empName, siteName);
    }

    // ── Unlock + recompute (Web team audit, 2026-07-31): an HR-adjusted summary is protected
    // from automatic recompute forever (see the adjustmentReason != null skip above) — but once
    // new source data legitimately arrives (e.g. a late-approved checkin), HR needs a deliberate
    // way to release that protection and re-run the normal aggregation, with a recorded reason
    // and an audit trail (not just silently clearing the field). ────────────────────────────────

    @Transactional
    public AttendanceSummaryResponse unlockAndRecompute(UUID tenantId, UUID summaryId, String reason,
                                                         UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("attendance:adjust")) {
                throw new AccessDeniedException("You do not have permission to unlock attendance summaries");
            }
        }

        AttendanceSummary summary = summaryRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(summaryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance summary not found: " + summaryId));

        if (!siteScopeService.isSiteAllowed(callerUserId, tenantId, summary.getSiteId(), callerIsPlatformAdmin)) {
            throw new AccessDeniedException("You do not have permission to unlock attendance for this site");
        }

        // Captured into locals, not read off `summary` later — recompute() below fetches and
        // mutates the SAME managed entity instance (same Hibernate persistence-context identity),
        // so `summary`'s fields reflect the post-recompute values by the time the audit log call
        // happens if read directly off the entity.
        String previousAdjustmentReason = summary.getAdjustmentReason();
        int previousTotalWorkMinutes = summary.getTotalWorkMinutes();

        Site site = siteRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(summary.getSiteId(), tenantId)
                .orElse(null);
        String timezone = site != null ? site.getTimezone() : "UTC";

        summary.setAdjustmentReason(null);
        summaryRepository.save(summary);

        recompute(tenantId, summary.getEmployeeId(), summary.getSiteId(), summary.getAttendanceDate(),
                timezone, summary.getShiftId(), summary.getAssignmentId());

        AttendanceSummary recomputed = summaryRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(summaryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance summary not found: " + summaryId));

        auditLogService.record(
                tenantId, callerUserId, null,
                "AttendanceSummary", summaryId.toString(), "attendance_summary_unlock_and_recompute",
                Map.of("adjustmentReason", previousAdjustmentReason == null ? "" : previousAdjustmentReason,
                       "totalWorkMinutes", previousTotalWorkMinutes),
                Map.of("reason", reason,
                       "totalWorkMinutes", recomputed.getTotalWorkMinutes()),
                com.fams.shared.security.HttpRequestUtils.currentRequestId(), null, null);

        log.info("HR unlocked and recomputed attendance summary: summaryId={} by={} reason={} " +
                "previousAdjustmentReason={}", summaryId, callerUserId, reason, previousAdjustmentReason);

        String empName  = resolveEmployeeName(tenantId, recomputed.getEmployeeId());
        String siteName = resolveSiteName(tenantId, recomputed.getSiteId());
        return toResponse(recomputed, empName, siteName);
    }

    // ── Mapper ─────────────────────────────────────────────────────────────────

    private AttendanceSummaryResponse toResponse(AttendanceSummary a, String employeeName, String siteName) {
        return AttendanceSummaryResponse.builder()
                .id(a.getId())
                .tenantId(a.getTenantId())
                .employeeId(a.getEmployeeId())
                .employeeName(employeeName)
                .siteId(a.getSiteId())
                .siteName(siteName)
                .shiftId(a.getShiftId())
                .assignmentId(a.getAssignmentId())
                .attendanceDate(a.getAttendanceDate())
                .firstCheckinAt(a.getFirstCheckinAt())
                .lastCheckoutAt(a.getLastCheckoutAt())
                .totalWorkMinutes(a.getTotalWorkMinutes())
                .sessionCount(a.getSessionCount())
                .status(a.getStatus())
                .late(a.isLate())
                .lateMinutes(a.getLateMinutes())
                .earlyLeave(a.isEarlyLeave())
                .earlyLeaveMinutes(a.getEarlyLeaveMinutes())
                .otMinutes(a.getOtMinutes())
                .missingCheckout(a.isMissingCheckout())
                .hasPendingReviewSession(a.isHasPendingReviewSession())
                .hasRejectedSession(a.isHasRejectedSession())
                .hasRandomCheckFailure(a.isHasRandomCheckFailure())
                .otDailyLimitExceeded(a.isOtDailyLimitExceeded())
                .otWeeklyLimitExceeded(a.isOtWeeklyLimitExceeded())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .adjustmentReason(a.getAdjustmentReason())
                .build();
    }

    // ── Name hydration helpers ─────────────────────────────────────────────────

    private Map<UUID, String> buildEmployeeNameMap(UUID tenantId, Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return employeeRepository.findAllByTenantIdAndIdInAndDeletedAtIsNull(tenantId, ids)
                .stream()
                .collect(Collectors.toMap(
                        Employee::getId,
                        e -> (e.getFirstName() + " " + e.getLastName()).trim()));
    }

    private Map<UUID, String> buildSiteNameMap(UUID tenantId, Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return siteRepository.findAllByTenantIdAndIdInAndDeletedAtIsNull(tenantId, ids)
                .stream()
                .collect(Collectors.toMap(Site::getId, Site::getName));
    }

    private String resolveEmployeeName(UUID tenantId, UUID employeeId) {
        return employeeRepository.findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .map(e -> (e.getFirstName() + " " + e.getLastName()).trim())
                .orElse(null);
    }

    private String resolveSiteName(UUID tenantId, UUID siteId) {
        return siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .map(Site::getName)
                .orElse(null);
    }
}
