package com.fams.modules.attendance.service;

import com.fams.modules.attendance.dto.response.AttendanceHrMonthlyResponse;
import com.fams.modules.attendance.dto.response.AttendanceMonthlyResponse;
import com.fams.modules.attendance.dto.response.AttendanceSummaryResponse;
import com.fams.modules.attendance.entity.AttendanceSummary;
import com.fams.modules.attendance.repository.AttendanceSummaryRepository;
import com.fams.modules.attendance.specification.AttendanceSummarySpecification;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
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
    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRoleRepository userRoleRepository;

    public AttendanceSummaryService(AttendanceSummaryRepository summaryRepository,
                                    CheckinRepository checkinRepository,
                                    SiteRepository siteRepository,
                                    ShiftRepository shiftRepository,
                                    EmployeeRepository employeeRepository,
                                    UserRoleRepository userRoleRepository) {
        this.summaryRepository = summaryRepository;
        this.checkinRepository = checkinRepository;
        this.siteRepository = siteRepository;
        this.shiftRepository = shiftRepository;
        this.employeeRepository = employeeRepository;
        this.userRoleRepository = userRoleRepository;
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
     * Nightly catch-up: recomputes summaries for all check-in records whose local
     * attendance date equals {@code targetDate}. Called by AttendanceSummaryJob.
     */
    @Transactional
    public void recomputeForDate(LocalDate targetDate) {
        // Use a ±1 day UTC window to capture all timezone offsets (UTC-12 to UTC+14).
        OffsetDateTime from = targetDate.minusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to   = targetDate.plusDays(2).atStartOfDay().atOffset(ZoneOffset.UTC);

        List<CheckinRecord> candidates = checkinRepository.findCheckinsBetween(from, to);
        if (candidates.isEmpty()) {
            log.info("No check-ins found in UTC window {} to {} for target date {}", from, to, targetDate);
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

        List<CheckinRecord> sessions = checkinRepository
                .findSessionsForDateRange(tenantId, employeeId, siteId, startOfDay, endOfDay);

        if (sessions.isEmpty()) {
            return;
        }

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

        // Tasks 81-83: late, early-leave, and OT detection all require the shift
        boolean isLate = false;
        int lateMinutes = 0;
        boolean isEarlyLeave = false;
        int earlyLeaveMinutes = 0;
        int otMinutes = 0;

        if (shiftId != null) {
            Shift shift = shiftRepository
                    .findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(shiftId, siteId, tenantId)
                    .orElse(null);
            if (shift != null) {
                // For overnight shifts the shift end is on the next calendar day.
                LocalDate endDate = shift.isAllowOvernight() ? date.plusDays(1) : date;
                ZonedDateTime shiftStart = ZonedDateTime.of(date, shift.getStartTime(), zone);
                ZonedDateTime shiftEnd   = ZonedDateTime.of(endDate, shift.getEndTime(), zone);

                // Task 81: late detection — first check-in vs shift start
                if (firstCheckinAt != null) {
                    ZonedDateTime actualStart = firstCheckinAt.atZoneSameInstant(zone);
                    if (actualStart.isAfter(shiftStart)) {
                        lateMinutes = (int) Duration.between(shiftStart, actualStart).toMinutes();
                        isLate = lateMinutes > 0;
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
                if (shift.isAllowOvertime()) {
                    ZonedDateTime otCap = shiftEnd.plusMinutes(shift.getLateCheckoutMinutes());
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
        }

        // Task 84: missing_checkout — only flagged for past days with open sessions.
        // A session open on today's date is still in progress, not yet "missing".
        boolean missingCheckout = hasOpenSession && date.isBefore(LocalDate.now(zone));

        AttendanceSummary summary = summaryRepository
                .findByTenantIdAndEmployeeIdAndSiteIdAndAttendanceDateAndDeletedAtIsNull(
                        tenantId, employeeId, siteId, date)
                .orElse(AttendanceSummary.builder()
                        .tenantId(tenantId)
                        .employeeId(employeeId)
                        .siteId(siteId)
                        .shiftId(shiftId)
                        .assignmentId(assignmentId)
                        .attendanceDate(date)
                        .build());

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
        summary.setMissingCheckout(missingCheckout);

        summaryRepository.save(summary);
        log.info("Attendance summary upserted: tenantId={} employeeId={} siteId={} date={} status={} " +
                "workMinutes={} isLate={} lateMinutes={} isEarlyLeave={} earlyLeaveMinutes={} " +
                "otMinutes={} missingCheckout={}",
                tenantId, employeeId, siteId, date, status, totalWorkMinutes,
                isLate, lateMinutes, isEarlyLeave, earlyLeaveMinutes, otMinutes, missingCheckout);
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

        Specification<AttendanceSummary> spec =
                AttendanceSummarySpecification.build(tenantId, employeeId, siteId, status, from, to);
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "attendanceDate"));

        Page<AttendanceSummaryResponse> resultPage = summaryRepository
                .findAll(spec, pageable)
                .map(this::toResponse);

        log.info("HR attendance list: tenantId={} employeeId={} siteId={} total={}",
                tenantId, employeeId, siteId, resultPage.getTotalElements());

        return PageResponse.from(resultPage);
    }

    @Transactional(readOnly = true)
    public AttendanceSummaryResponse getSummary(UUID tenantId, UUID summaryId,
                                                 UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("attendance:read")) {
                throw new AccessDeniedException("You do not have permission to view this attendance summary");
            }
        }

        AttendanceSummary summary = summaryRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(summaryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance summary not found: " + summaryId));

        return toResponse(summary);
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceSummaryResponse> getMyAttendance(UUID tenantId, UUID callerUserId,
                                                                    LocalDate from, LocalDate to,
                                                                    int page, int size) {
        Employee employee = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No employee profile found for this user in tenant: " + tenantId));

        Specification<AttendanceSummary> spec =
                AttendanceSummarySpecification.build(tenantId, employee.getId(), null, null, from, to);
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "attendanceDate"));

        Page<AttendanceSummaryResponse> resultPage = summaryRepository
                .findAll(spec, pageable)
                .map(this::toResponse);

        log.info("Employee attendance: tenantId={} employeeId={} total={}",
                tenantId, employee.getId(), resultPage.getTotalElements());

        return PageResponse.from(resultPage);
    }

    @Transactional(readOnly = true)
    public AttendanceMonthlyResponse getMyMonthlyAttendance(UUID tenantId, UUID callerUserId,
                                                             int year, int month) {
        Employee employee = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No employee profile found for this user in tenant: " + tenantId));

        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to   = from.plusMonths(1);

        List<AttendanceSummary> rows = summaryRepository
                .findByTenantIdAndEmployeeIdAndDateRange(tenantId, employee.getId(), from, to);

        int presentDays          = rows.size();
        int totalWorkMinutes     = rows.stream().mapToInt(AttendanceSummary::getTotalWorkMinutes).sum();
        int lateDays             = (int) rows.stream().filter(AttendanceSummary::isLate).count();
        int totalLateMinutes     = rows.stream().mapToInt(AttendanceSummary::getLateMinutes).sum();
        int earlyLeaveDays       = (int) rows.stream().filter(AttendanceSummary::isEarlyLeave).count();
        int totalEarlyLeaveMinutes = rows.stream().mapToInt(AttendanceSummary::getEarlyLeaveMinutes).sum();
        int totalOtMinutes       = rows.stream().mapToInt(AttendanceSummary::getOtMinutes).sum();
        int missingCheckoutDays  = (int) rows.stream().filter(AttendanceSummary::isMissingCheckout).count();

        List<AttendanceSummaryResponse> daily = rows.stream().map(this::toResponse).collect(Collectors.toList());

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
                .dailySummaries(daily)
                .build();
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceHrMonthlyResponse> listMonthlyAttendance(
            UUID tenantId, UUID employeeId, UUID siteId, int year, int month,
            int page, int size, UUID callerUserId, boolean callerIsPlatformAdmin) {

        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("attendance:list")) {
                throw new AccessDeniedException("You do not have permission to list attendance summaries");
            }
        }

        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to   = from.plusMonths(1);

        Specification<AttendanceSummary> spec =
                AttendanceSummarySpecification.build(tenantId, employeeId, siteId, null, from, to);

        List<AttendanceSummary> rows = summaryRepository.findAll(spec);

        // Group by employeeId+siteId, preserving insertion order for stable pagination
        Map<String, List<AttendanceSummary>> grouped = new LinkedHashMap<>();
        for (AttendanceSummary row : rows) {
            String key = row.getEmployeeId() + ":" + row.getSiteId();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<AttendanceHrMonthlyResponse> aggregated = grouped.values().stream()
                .map(group -> {
                    AttendanceSummary first = group.get(0);
                    return AttendanceHrMonthlyResponse.builder()
                            .tenantId(tenantId)
                            .employeeId(first.getEmployeeId())
                            .siteId(first.getSiteId())
                            .year(year)
                            .month(month)
                            .presentDays(group.size())
                            .totalWorkMinutes(group.stream().mapToInt(AttendanceSummary::getTotalWorkMinutes).sum())
                            .lateDays((int) group.stream().filter(AttendanceSummary::isLate).count())
                            .totalLateMinutes(group.stream().mapToInt(AttendanceSummary::getLateMinutes).sum())
                            .earlyLeaveDays((int) group.stream().filter(AttendanceSummary::isEarlyLeave).count())
                            .totalEarlyLeaveMinutes(group.stream().mapToInt(AttendanceSummary::getEarlyLeaveMinutes).sum())
                            .totalOtMinutes(group.stream().mapToInt(AttendanceSummary::getOtMinutes).sum())
                            .missingCheckoutDays((int) group.stream().filter(AttendanceSummary::isMissingCheckout).count())
                            .build();
                })
                .collect(Collectors.toList());

        int total = aggregated.size();
        int fromIdx = Math.min(page * size, total);
        int toIdx   = Math.min(fromIdx + size, total);
        List<AttendanceHrMonthlyResponse> pageContent = aggregated.subList(fromIdx, toIdx);

        PageRequest pageable = PageRequest.of(page, size);
        Page<AttendanceHrMonthlyResponse> resultPage = new PageImpl<>(pageContent, pageable, total);

        log.info("HR monthly list: tenantId={} employeeId={} siteId={} {}/{} groups={}",
                tenantId, employeeId, siteId, year, month, total);

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
            if (!perms.contains("attendance:list")) {
                throw new AccessDeniedException("You do not have permission to adjust attendance summaries");
            }
        }

        AttendanceSummary summary = summaryRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(summaryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance summary not found: " + summaryId));

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

        log.info("HR adjusted attendance summary: summaryId={} by={} reason={}",
                summaryId, callerUserId, request.getReason());

        return toResponse(summary);
    }

    // ── Mapper ─────────────────────────────────────────────────────────────────

    private AttendanceSummaryResponse toResponse(AttendanceSummary a) {
        return AttendanceSummaryResponse.builder()
                .id(a.getId())
                .tenantId(a.getTenantId())
                .employeeId(a.getEmployeeId())
                .siteId(a.getSiteId())
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
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .adjustmentReason(a.getAdjustmentReason())
                .build();
    }
}
