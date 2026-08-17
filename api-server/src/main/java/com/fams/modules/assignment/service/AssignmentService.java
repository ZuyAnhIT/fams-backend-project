package com.fams.modules.assignment.service;

import com.fams.modules.assignment.dto.request.CreateAssignmentRequest;
import com.fams.modules.assignment.dto.request.UpdateAssignmentRequest;
import com.fams.modules.assignment.dto.response.AssignmentResponse;
import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.assignment.specification.AssignmentSpecification;
import com.fams.modules.assignment.util.DayOfWeekBitmask;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.randomcheck.service.ScheduledCheckCancelService;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class AssignmentService {

    private static final Set<String> SORTABLE_FIELDS =
            Set.of("startDate", "endDate", "role", "status", "createdAt");

    private final AssignmentRepository assignmentRepository;
    private final SiteRepository siteRepository;
    private final EmployeeRepository employeeRepository;
    private final ShiftRepository shiftRepository;
    private final TenantRepository tenantRepository;
    private final UserRoleRepository userRoleRepository;
    private final SiteScopeService siteScopeService;
    private final ScheduledCheckCancelService scheduledCheckCancelService;
    private final AuditLogService auditLogService;

    public AssignmentService(AssignmentRepository assignmentRepository,
                             SiteRepository siteRepository,
                             EmployeeRepository employeeRepository,
                             ShiftRepository shiftRepository,
                             TenantRepository tenantRepository,
                             UserRoleRepository userRoleRepository,
                             SiteScopeService siteScopeService,
                             ScheduledCheckCancelService scheduledCheckCancelService,
                             AuditLogService auditLogService) {
        this.assignmentRepository = assignmentRepository;
        this.siteRepository = siteRepository;
        this.employeeRepository = employeeRepository;
        this.shiftRepository = shiftRepository;
        this.tenantRepository = tenantRepository;
        this.userRoleRepository = userRoleRepository;
        this.siteScopeService = siteScopeService;
        this.scheduledCheckCancelService = scheduledCheckCancelService;
        this.auditLogService = auditLogService;
    }

    private Map<String, Object> assignmentAuditSnapshot(Assignment a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("siteId", a.getSiteId());
        map.put("employeeId", a.getEmployeeId());
        map.put("shiftId", a.getShiftId());
        map.put("startDate", String.valueOf(a.getStartDate()));
        map.put("endDate", a.getEndDate() != null ? String.valueOf(a.getEndDate()) : null);
        map.put("daysOfWeek", a.getDaysOfWeek());
        map.put("role", a.getRole());
        map.put("status", a.getStatus());
        return map;
    }

    private void recordAudit(UUID tenantId, UUID actorId, UUID assignmentId, String action,
                              Map<String, Object> before, Map<String, Object> after) {
        try {
            auditLogService.record(
                    tenantId, actorId, null,
                    "Assignment", assignmentId.toString(), action,
                    before, after,
                    HttpRequestUtils.currentRequestId(),
                    HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception e) {
            log.warn("Failed to record audit log action={} assignmentId={}: {}", action, assignmentId, e.getMessage());
        }
    }

    private void assertSiteInScope(UUID callerUserId, UUID tenantId, UUID siteId, boolean callerIsPlatformAdmin) {
        if (!siteScopeService.isSiteAllowed(callerUserId, tenantId, siteId, callerIsPlatformAdmin)) {
            throw new AccessDeniedException("You do not have permission to act on this site");
        }
    }

    /** An employee cannot physically be at two sites (or two shifts at the SAME site) during
     *  overlapping hours. Blocks creating/updating an assignment only when its actual SHIFT TIME
     *  WINDOW (in each site's own timezone, overnight shifts spanning into the next day)
     *  overlaps another active assignment for the same employee — at a DIFFERENT site, or the
     *  SAME site — on a calendar day both assignments' date range + daysOfWeek can actually
     *  reach. Same day, non-overlapping hours (e.g. Site A morning, Site B evening; or a current
     *  assignment ending before a future one starts at the same site) is explicitly ALLOWED —
     *  this is real, common multi-site staffing and forward scheduling (#63: replaced the old
     *  "at most one active assignment per employee+site, period" constraint, which blocked
     *  pre-creating a non-overlapping future assignment).
     *
     *  excludeAssignmentId lets updateAssignment re-check without conflicting with itself.
     *
     *  Note: does not account for daylight-saving transitions (immaterial for this system's
     *  current tenant timezones, all DST-free) — a representative calendar date is used to
     *  resolve each side's local time window into a comparable UTC instant. */
    private void assertNoConflicts(UUID tenantId, UUID employeeId, UUID siteId, UUID excludeAssignmentId,
                                   UUID shiftId, LocalDate startDate, LocalDate endDate, Short daysOfWeekBitmask) {
        List<Assignment> crossSiteConflicts = assignmentRepository.findActiveConflictsAtOtherSites(
                tenantId, employeeId, siteId, startDate, endDate, daysOfWeekBitmask);
        assertNoTimeOverlap(tenantId, siteId, shiftId, startDate, endDate, daysOfWeekBitmask, crossSiteConflicts, true);

        List<Assignment> sameSiteConflicts = assignmentRepository.findActiveConflictsAtSameSite(
                tenantId, employeeId, siteId, excludeAssignmentId, startDate, endDate, daysOfWeekBitmask);
        assertNoTimeOverlap(tenantId, siteId, shiftId, startDate, endDate, daysOfWeekBitmask, sameSiteConflicts, false);
    }

    private void assertNoTimeOverlap(UUID tenantId, UUID siteId, UUID shiftId,
                                     LocalDate startDate, LocalDate endDate, Short daysOfWeekBitmask,
                                     List<Assignment> coarseConflicts, boolean crossSite) {
        if (coarseConflicts.isEmpty()) {
            return;
        }

        LocalDate farFuture = LocalDate.of(9999, 12, 31);
        LocalDate myEnd = endDate != null ? endDate : farFuture;
        Set<DayOfWeek> mine = DayOfWeekBitmask.fromBitmask(daysOfWeekBitmask);

        for (Assignment other : coarseConflicts) {
            LocalDate overlapStart = startDate.isAfter(other.getStartDate()) ? startDate : other.getStartDate();
            LocalDate otherEnd = other.getEndDate() != null ? other.getEndDate() : farFuture;
            LocalDate overlapEnd = myEnd.isBefore(otherEnd) ? myEnd : otherEnd;
            if (overlapStart.isAfter(overlapEnd)) {
                continue;
            }

            Set<DayOfWeek> theirs = DayOfWeekBitmask.fromBitmask(other.getDaysOfWeek());
            for (DayOfWeek dow : DayOfWeek.values()) {
                if (mine != null && !mine.contains(dow)) continue;
                if (theirs != null && !theirs.contains(dow)) continue;

                LocalDate onDate = firstDateOnOrAfterWithWeekday(overlapStart, dow);
                if (onDate == null || onDate.isAfter(overlapEnd)) {
                    continue;
                }

                if (timeWindowsOverlap(siteId, shiftId, other.getSiteId(), other.getShiftId(), onDate)) {
                    if (crossSite) {
                        String otherSiteName = siteRepository
                                .findByIdAndTenantIdAndDeletedAtIsNull(other.getSiteId(), tenantId)
                                .map(com.fams.modules.site.entity.Site::getName)
                                .orElse(other.getSiteId().toString());
                        throw new DuplicateResourceException(
                                "Employee already has an overlapping active assignment at site '" + otherSiteName
                                        + "' during this period — the shift hours overlap");
                    } else {
                        throw new DuplicateResourceException(
                                "Employee already has an overlapping active assignment at this site during this "
                                        + "period — the shift hours overlap with an existing assignment");
                    }
                }
            }
        }
    }

    private LocalDate firstDateOnOrAfterWithWeekday(LocalDate from, DayOfWeek weekday) {
        int diff = (weekday.getValue() - from.getDayOfWeek().getValue() + 7) % 7;
        return from.plusDays(diff);
    }

    /** True if the [startTime,endTime) window of (siteId1, shiftId1) on {@code onDate} overlaps
     *  the window of (siteId2, shiftId2) on the same calendar date, comparing actual UTC
     *  instants (each site's own timezone applied) rather than raw local times. A null shiftId
     *  is treated as occupying the full local calendar day. */
    private boolean timeWindowsOverlap(UUID siteId1, UUID shiftId1, UUID siteId2, UUID shiftId2, LocalDate onDate) {
        Instant[] w1 = resolveTimeWindow(siteId1, shiftId1, onDate);
        Instant[] w2 = resolveTimeWindow(siteId2, shiftId2, onDate);
        return w1[0].isBefore(w2[1]) && w2[0].isBefore(w1[1]);
    }

    private Instant[] resolveTimeWindow(UUID siteId, UUID shiftId, LocalDate onDate) {
        String timezone = siteRepository.findById(siteId)
                .map(com.fams.modules.site.entity.Site::getTimezone)
                .orElse("UTC");
        ZoneId zone = ZoneId.of(timezone);

        if (shiftId == null) {
            Instant start = onDate.atStartOfDay(zone).toInstant();
            Instant end = onDate.plusDays(1).atStartOfDay(zone).toInstant();
            return new Instant[]{start, end};
        }

        return shiftRepository.findById(shiftId)
                .map(shift -> {
                    LocalDate endDate = shift.isAllowOvernight() ? onDate.plusDays(1) : onDate;
                    Instant start = ZonedDateTime.of(onDate, shift.getStartTime(), zone).toInstant();
                    Instant end = ZonedDateTime.of(endDate, shift.getEndTime(), zone).toInstant();
                    return new Instant[]{start, end};
                })
                .orElseGet(() -> new Instant[]{
                        onDate.atStartOfDay(zone).toInstant(),
                        onDate.plusDays(1).atStartOfDay(zone).toInstant()
                });
    }

    /** A single (assignment, calendar-day) occurrence resolved into concrete site-timezone
     *  instants — the shared source of truth for "is this assignment relevant right now",
     *  used by both HR-facing conflict checks here and the App's available-sites/checkin flow
     *  (CheckinService), so the two surfaces can never disagree on what "today" or "on time"
     *  means for a given site. */
    public record AssignmentAvailability(
            Assignment assignment,
            LocalDate effectiveDate,
            ZoneId siteZone,
            Instant checkinAllowedFrom,
            Instant checkinAllowedUntil,
            Instant shiftStartInstant,
            Instant shiftEndInstant) {
    }

    /** Resolves every assignment that is relevant to this employee "right now", using each
     *  assignment's own SITE timezone (not the server's default zone) to decide what calendar
     *  day it is and whether an overnight shift that started site-local "yesterday" is still
     *  open. A shift-less assignment occupies its whole site-local calendar day. Used by
     *  CheckinService for both the available-sites listing and submitCheckin, so date/timezone
     *  resolution is identical in both places (see class-level javadoc on AssignmentAvailability). */
    @Transactional(readOnly = true)
    public List<AssignmentAvailability> resolveAvailableAssignmentsNow(UUID tenantId, UUID employeeId) {
        Instant now = Instant.now();
        LocalDate utcToday = LocalDate.now(ZoneOffset.UTC);

        // Coarse net: site timezones range roughly UTC-12..UTC+14, so a site-local calendar day
        // can differ from UTC's by up to a day in either direction. Cast wide here; the precise
        // per-assignment site-local filtering happens below.
        Map<UUID, Assignment> candidates = new LinkedHashMap<>();
        for (LocalDate anchor : List.of(utcToday.minusDays(1), utcToday, utcToday.plusDays(1))) {
            for (Assignment a : assignmentRepository.findActiveAssignmentsForEmployeeOnDate(
                    tenantId, employeeId, anchor, DayOfWeekBitmask.bitForDate(anchor))) {
                candidates.putIfAbsent(a.getId(), a);
            }
        }

        List<AssignmentAvailability> result = new java.util.ArrayList<>();
        for (Assignment a : candidates.values()) {
            resolveIfRelevantNow(a, now).ifPresent(result::add);
        }
        return result;
    }

    /** Same resolution as {@link #resolveAvailableAssignmentsNow}, narrowed to one site — used
     *  by submitCheckin, which already knows which site the employee is trying to check into. */
    @Transactional(readOnly = true)
    public Optional<AssignmentAvailability> resolveAvailableAssignmentForSiteNow(
            UUID tenantId, UUID employeeId, UUID siteId) {
        return resolveAvailableAssignmentsNow(tenantId, employeeId).stream()
                .filter(av -> av.assignment().getSiteId().equals(siteId))
                .findFirst();
    }

    private Optional<AssignmentAvailability> resolveIfRelevantNow(Assignment a, Instant now) {
        String timezone = siteRepository.findById(a.getSiteId())
                .map(com.fams.modules.site.entity.Site::getTimezone)
                .orElse("UTC");
        ZoneId zone = ZoneId.of(timezone);
        LocalDate siteToday = now.atZone(zone).toLocalDate();

        // Prefer site-local "today"; fall back to "yesterday" only for an overnight shift that
        // is still genuinely open (now < shiftEnd) — never resurrect a shift-less assignment or
        // a same-day shift from the previous day, that would just be stale clutter.
        for (LocalDate candidateDate : List.of(siteToday, siteToday.minusDays(1))) {
            if (!withinDateRange(a, candidateDate) || !matchesDayOfWeek(a, candidateDate)) {
                continue;
            }

            if (a.getShiftId() == null) {
                if (!candidateDate.equals(siteToday)) {
                    continue; // no shift = whole calendar day only, no overnight carry-over
                }
                Instant from = candidateDate.atStartOfDay(zone).toInstant();
                Instant until = candidateDate.plusDays(1).atStartOfDay(zone).toInstant();
                return Optional.of(new AssignmentAvailability(a, candidateDate, zone, from, until, null, null));
            }

            com.fams.modules.shift.entity.Shift shift = shiftRepository.findById(a.getShiftId()).orElse(null);
            if (shift == null) {
                continue;
            }
            LocalDate shiftEndDate = shift.isAllowOvernight() ? candidateDate.plusDays(1) : candidateDate;
            Instant shiftStart = ZonedDateTime.of(candidateDate, shift.getStartTime(), zone).toInstant();
            Instant shiftEnd = ZonedDateTime.of(shiftEndDate, shift.getEndTime(), zone).toInstant();

            if (!candidateDate.equals(siteToday) && !now.isBefore(shiftEnd)) {
                continue; // yesterday's occurrence already ended — not relevant "now"
            }

            Instant checkinFrom = shiftStart.minusSeconds(shift.getEarlyCheckinMinutes() * 60L);
            return Optional.of(new AssignmentAvailability(
                    a, candidateDate, zone, checkinFrom, shiftEnd, shiftStart, shiftEnd));
        }
        return Optional.empty();
    }

    private boolean withinDateRange(Assignment a, LocalDate date) {
        return !date.isBefore(a.getStartDate()) && (a.getEndDate() == null || !date.isAfter(a.getEndDate()));
    }

    private boolean matchesDayOfWeek(Assignment a, LocalDate date) {
        Set<DayOfWeek> days = DayOfWeekBitmask.fromBitmask(a.getDaysOfWeek());
        return days == null || days.contains(date.getDayOfWeek());
    }

    @Transactional
    public AssignmentResponse createAssignment(UUID tenantId, UUID siteId,
                                               CreateAssignmentRequest request,
                                               UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("assignments:create")) {
                throw new AccessDeniedException(
                        "You do not have permission to create assignments in this tenant");
            }
        }
        assertSiteInScope(callerUserId, tenantId, siteId, callerIsPlatformAdmin);

        com.fams.modules.site.entity.Site site = siteRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));
        if (!"active".equals(site.getStatus())) {
            throw new IllegalArgumentException(
                    "Site '" + site.getName() + "' is inactive and can no longer receive new assignments");
        }

        com.fams.modules.employee.entity.Employee employee = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(request.getEmployeeId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee not found: " + request.getEmployeeId()));

        if ("terminated".equals(employee.getStatus())) {
            throw new IllegalArgumentException(
                    "Cannot assign a terminated employee to a site");
        }

        if (request.getShiftId() != null) {
            com.fams.modules.shift.entity.Shift shift = shiftRepository
                    .findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(request.getShiftId(), siteId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Shift not found for this site: " + request.getShiftId()));
            if (!"active".equals(shift.getStatus())) {
                throw new IllegalArgumentException(
                        "Shift '" + shift.getName() + "' is inactive and can no longer be assigned");
            }
        }

        if (request.getEndDate() != null && request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }

        if (request.getDaysOfWeek() != null && request.getDaysOfWeek().isEmpty()) {
            throw new IllegalArgumentException("daysOfWeek must not be empty; omit it to allow every day");
        }

        Short daysOfWeekBitmask = DayOfWeekBitmask.toBitmask(request.getDaysOfWeek());
        assertNoConflicts(tenantId, request.getEmployeeId(), siteId, null, request.getShiftId(),
                request.getStartDate(), request.getEndDate(), daysOfWeekBitmask);

        Assignment assignment = Assignment.builder()
                .tenantId(tenantId)
                .siteId(siteId)
                .employeeId(request.getEmployeeId())
                .shiftId(request.getShiftId())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .daysOfWeek(daysOfWeekBitmask)
                .role(request.getRole() != null ? request.getRole() : "worker")
                .status("active")
                .notes(request.getNotes())
                .createdBy(callerUserId)
                .build();

        assignmentRepository.save(assignment);
        log.info("Assignment created: id={} employeeId={} siteId={} tenantId={} by={}",
                assignment.getId(), request.getEmployeeId(), siteId, tenantId, callerUserId);
        recordAudit(tenantId, callerUserId, assignment.getId(), "assignment_created", null, assignmentAuditSnapshot(assignment));
        return toResponse(assignment);
    }

    @Transactional(readOnly = true)
    public PageResponse<AssignmentResponse> listAssignments(UUID tenantId, UUID siteId,
                                                             String status, String role,
                                                             UUID employeeId, UUID shiftId,
                                                             LocalDate dateRangeFrom, LocalDate dateRangeTo,
                                                             String sortBy, String sortDir,
                                                             int page, int size,
                                                             UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("assignments:list")) {
                throw new AccessDeniedException(
                        "You do not have permission to list assignments in this tenant");
            }
        }
        assertSiteInScope(callerUserId, tenantId, siteId, callerIsPlatformAdmin);

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        String resolvedSortBy = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "startDate";
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, resolvedSortBy));

        Specification<Assignment> spec = AssignmentSpecification.build(
                siteId, tenantId, StringUtils.hasText(status) ? status : null,
                StringUtils.hasText(role) ? role : null, employeeId, shiftId,
                dateRangeFrom, dateRangeTo);
        Page<Assignment> resultPage = assignmentRepository.findAll(spec, pageable);

        List<UUID> employeeIds = resultPage.getContent().stream()
                .map(Assignment::getEmployeeId).distinct().toList();
        List<UUID> shiftIds = resultPage.getContent().stream()
                .map(Assignment::getShiftId).filter(java.util.Objects::nonNull).distinct().toList();

        Map<UUID, com.fams.modules.employee.entity.Employee> employeesById = employeeIds.isEmpty()
                ? Map.of()
                : employeeRepository.findAllById(employeeIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.fams.modules.employee.entity.Employee::getId, e -> e));
        Map<UUID, com.fams.modules.shift.entity.Shift> shiftsById = shiftIds.isEmpty()
                ? Map.of()
                : shiftRepository.findAllById(shiftIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.fams.modules.shift.entity.Shift::getId, s -> s));

        return PageResponse.from(resultPage.map(a -> toResponse(a, employeesById, shiftsById)));
    }

    @Transactional
    public AssignmentResponse updateAssignment(UUID tenantId, UUID siteId, UUID assignmentId,
                                               UpdateAssignmentRequest request,
                                               UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("assignments:update")) {
                throw new AccessDeniedException(
                        "You do not have permission to update assignments in this tenant");
            }
        }
        assertSiteInScope(callerUserId, tenantId, siteId, callerIsPlatformAdmin);

        com.fams.modules.site.entity.Site site = siteRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));
        if (!"active".equals(site.getStatus())) {
            throw new IllegalArgumentException(
                    "Site '" + site.getName() + "' is inactive — its assignments can no longer be modified");
        }

        Assignment assignment = assignmentRepository
                .findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(assignmentId, siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found: " + assignmentId));

        if ("cancelled".equals(assignment.getStatus())) {
            throw new IllegalArgumentException(
                    "Cannot modify a cancelled assignment — it is a closed record kept for history");
        }

        Map<String, Object> before = assignmentAuditSnapshot(assignment);

        if (request.isClearShift()) {
            assignment.setShiftId(null);
        } else if (request.getShiftId() != null) {
            com.fams.modules.shift.entity.Shift shift = shiftRepository
                    .findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(request.getShiftId(), siteId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Shift not found for this site: " + request.getShiftId()));
            if (!"active".equals(shift.getStatus())) {
                throw new IllegalArgumentException(
                        "Shift '" + shift.getName() + "' is inactive and can no longer be assigned");
            }
            assignment.setShiftId(request.getShiftId());
        }

        if (request.getStartDate() != null) assignment.setStartDate(request.getStartDate());

        if (request.isClearEndDate()) {
            assignment.setEndDate(null);
        } else if (request.getEndDate() != null) {
            assignment.setEndDate(request.getEndDate());
        }

        LocalDate effectiveEnd = assignment.getEndDate();
        if (effectiveEnd != null && effectiveEnd.isBefore(assignment.getStartDate())) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }

        if (request.isClearDaysOfWeek()) {
            assignment.setDaysOfWeek(null);
        } else if (request.getDaysOfWeek() != null) {
            if (request.getDaysOfWeek().isEmpty()) {
                throw new IllegalArgumentException(
                        "daysOfWeek must not be empty; use clearDaysOfWeek to allow every day");
            }
            assignment.setDaysOfWeek(DayOfWeekBitmask.toBitmask(request.getDaysOfWeek()));
        }

        if (StringUtils.hasText(request.getRole())) assignment.setRole(request.getRole());
        if (request.getNotes() != null) assignment.setNotes(request.getNotes().isBlank() ? null : request.getNotes());

        assertNoConflicts(tenantId, assignment.getEmployeeId(), siteId, assignmentId, assignment.getShiftId(),
                assignment.getStartDate(), assignment.getEndDate(), assignment.getDaysOfWeek());

        assignmentRepository.save(assignment);
        log.info("Assignment updated: id={} siteId={} tenantId={} by={}",
                assignmentId, siteId, tenantId, callerUserId);
        recordAudit(tenantId, callerUserId, assignmentId, "assignment_updated", before, assignmentAuditSnapshot(assignment));
        return toResponse(assignment);
    }

    @Transactional
    public void cancelAssignment(UUID tenantId, UUID siteId, UUID assignmentId,
                                 UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("assignments:delete")) {
                throw new AccessDeniedException(
                        "You do not have permission to cancel assignments in this tenant");
            }
        }
        assertSiteInScope(callerUserId, tenantId, siteId, callerIsPlatformAdmin);

        siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + siteId));

        Assignment assignment = assignmentRepository
                .findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(assignmentId, siteId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found: " + assignmentId));

        if ("cancelled".equals(assignment.getStatus())) {
            throw new IllegalArgumentException("Assignment is already cancelled");
        }

        Map<String, Object> before = assignmentAuditSnapshot(assignment);

        assignment.setStatus("cancelled");
        assignment.setCancelledBy(callerUserId);
        assignment.setCancelledAt(OffsetDateTime.now());
        assignmentRepository.save(assignment);
        log.info("Assignment cancelled: id={} siteId={} tenantId={} by={}",
                assignmentId, siteId, tenantId, callerUserId);
        recordAudit(tenantId, callerUserId, assignmentId, "assignment_cancelled", before, assignmentAuditSnapshot(assignment));

        int cancelled = scheduledCheckCancelService.cancelPendingByAssignment(assignmentId);
        if (cancelled > 0) {
            log.info("Auto-cancelled {} scheduled check(s) due to assignment cancellation id={}",
                    cancelled, assignmentId);
        }
    }

    @Transactional(readOnly = true)
    public long countActiveAssignmentsForSite(UUID siteId) {
        return assignmentRepository.countBySiteIdAndStatusAndDeletedAtIsNull(siteId, "active");
    }

    /** #54 gap fix: SiteService.getSiteDetail previously had no way to show "who's the
     *  supervisor" at all — this looks it up the same way the rest of the system already
     *  determines supervisor status (Assignment.role='supervisor'), not a separate sites column. */
    @Transactional(readOnly = true)
    public List<com.fams.modules.employee.entity.Employee> getActiveSupervisorEmployeesForSite(
            UUID tenantId, UUID siteId) {
        List<Assignment> supervisorAssignments = assignmentRepository
                .findByTenantIdAndSiteIdAndRoleAndStatusAndDeletedAtIsNull(
                        tenantId, siteId, "supervisor", "active");
        if (supervisorAssignments.isEmpty()) return List.of();
        List<UUID> employeeIds = supervisorAssignments.stream().map(Assignment::getEmployeeId).toList();
        return employeeRepository.findAllById(employeeIds);
    }

    /** An employee's own assignments across EVERY site they've ever been assigned to — unlike
     *  {@link #listAssignments}, which is always scoped to one site by its URL path, this is the
     *  cross-site "my assignments" view (App-facing self-service, no assignments:* permission
     *  required, same trust model as AttendanceSummaryService.getMyMonthlyAttendance: any
     *  authenticated user may see their own data). Most-recent-first, not paginated — an
     *  individual employee's assignment history is small enough that pagination isn't worth the
     *  added complexity (same judgment call already made for EmployeeService's assignment
     *  history list). */
    @Transactional(readOnly = true)
    public List<AssignmentResponse> getMyAssignments(UUID tenantId, UUID callerUserId) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        com.fams.modules.employee.entity.Employee employee = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No employee profile found for this user in tenant: " + tenantId));

        List<Assignment> assignments = assignmentRepository
                .findByTenantIdAndEmployeeIdAndDeletedAtIsNullOrderByStartDateDesc(tenantId, employee.getId());

        List<UUID> shiftIds = assignments.stream()
                .map(Assignment::getShiftId).filter(java.util.Objects::nonNull).distinct().toList();
        List<UUID> siteIds = assignments.stream().map(Assignment::getSiteId).distinct().toList();

        Map<UUID, com.fams.modules.shift.entity.Shift> shiftsById = shiftIds.isEmpty()
                ? Map.of()
                : shiftRepository.findAllById(shiftIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.fams.modules.shift.entity.Shift::getId, s -> s));
        Map<UUID, com.fams.modules.site.entity.Site> sitesById = siteIds.isEmpty()
                ? Map.of()
                : siteRepository.findAllById(siteIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.fams.modules.site.entity.Site::getId, s -> s));

        return assignments.stream()
                .map(a -> toResponse(a, employee,
                        a.getShiftId() != null ? shiftsById.get(a.getShiftId()) : null,
                        sitesById.get(a.getSiteId())))
                .toList();
    }

    /** Single-item variant — looks up employee/shift individually. For lists, use the
     *  batch-loaded overload below to avoid N+1 queries. */
    public AssignmentResponse toResponse(Assignment a) {
        com.fams.modules.employee.entity.Employee employee = employeeRepository.findById(a.getEmployeeId())
                .orElse(null);
        com.fams.modules.shift.entity.Shift shift = a.getShiftId() != null
                ? shiftRepository.findById(a.getShiftId()).orElse(null) : null;
        return toResponse(a, employee, shift);
    }

    private AssignmentResponse toResponse(Assignment a,
                                          Map<UUID, com.fams.modules.employee.entity.Employee> employeesById,
                                          Map<UUID, com.fams.modules.shift.entity.Shift> shiftsById) {
        return toResponse(a, employeesById.get(a.getEmployeeId()),
                a.getShiftId() != null ? shiftsById.get(a.getShiftId()) : null);
    }

    private AssignmentResponse toResponse(Assignment a,
                                          com.fams.modules.employee.entity.Employee employee,
                                          com.fams.modules.shift.entity.Shift shift) {
        return toResponse(a, employee, shift, null);
    }

    private AssignmentResponse toResponse(Assignment a,
                                          com.fams.modules.employee.entity.Employee employee,
                                          com.fams.modules.shift.entity.Shift shift,
                                          com.fams.modules.site.entity.Site site) {
        AssignmentResponse.SiteSummary siteSummary = site == null ? null :
                AssignmentResponse.SiteSummary.builder()
                        .id(site.getId())
                        .name(site.getName())
                        .build();

        AssignmentResponse.EmployeeSummary employeeSummary = employee == null ? null :
                AssignmentResponse.EmployeeSummary.builder()
                        .id(employee.getId())
                        .employeeCode(employee.getEmployeeCode())
                        .fullName(employee.getFirstName() + " " + employee.getLastName())
                        .status(employee.getStatus())
                        .build();

        AssignmentResponse.ShiftSummary shiftSummary = shift == null ? null :
                AssignmentResponse.ShiftSummary.builder()
                        .id(shift.getId())
                        .name(shift.getName())
                        .startTime(shift.getStartTime())
                        .endTime(shift.getEndTime())
                        .status(shift.getStatus())
                        .build();

        return AssignmentResponse.builder()
                .id(a.getId())
                .tenantId(a.getTenantId())
                .siteId(a.getSiteId())
                .employeeId(a.getEmployeeId())
                .shiftId(a.getShiftId())
                .siteSummary(siteSummary)
                .employeeSummary(employeeSummary)
                .shiftSummary(shiftSummary)
                .startDate(a.getStartDate())
                .endDate(a.getEndDate())
                .daysOfWeek(DayOfWeekBitmask.fromBitmask(a.getDaysOfWeek()))
                .role(a.getRole())
                .status(a.getStatus())
                .cancelledBy(a.getCancelledBy())
                .cancelledAt(a.getCancelledAt())
                .notes(a.getNotes())
                .createdBy(a.getCreatedBy())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}
