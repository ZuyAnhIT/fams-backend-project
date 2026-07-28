package com.fams.modules.checkin.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.service.AssignmentService;
import com.fams.modules.attendance.service.AttendanceSummaryService;
import com.fams.modules.checkin.dto.request.OverrideCheckinRequest;
import com.fams.modules.checkin.dto.request.SubmitCheckinRequest;
import com.fams.modules.checkin.dto.request.SubmitCheckoutRequest;
import com.fams.modules.checkin.specification.CheckinSpecification;
import com.fams.modules.checkin.dto.response.AvailableSiteResponse;
import com.fams.shared.ai.FaceVerifyJobPublisher;
import com.fams.modules.checkin.dto.response.CheckinDetailResponse;
import com.fams.modules.checkin.dto.response.CheckinResponse;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.geofence.entity.Geofence;
import com.fams.modules.geofence.repository.GeofenceRepository;
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.shared.exception.BusinessException;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.pagination.PageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CheckinService {

    private static final Set<String> SORTABLE_FIELDS =
            Set.of("checkInAt", "checkOutAt", "status", "siteId", "employeeId", "createdAt");

    private final EmployeeRepository employeeRepository;
    private final AssignmentService assignmentService;
    private final SiteRepository siteRepository;
    private final ShiftRepository shiftRepository;
    private final GeofenceRepository geofenceRepository;
    private final CheckinRepository checkinRepository;
    private final UserRoleRepository userRoleRepository;
    private final SiteScopeService siteScopeService;
    private final AttendanceSummaryService attendanceSummaryService;
    private final FaceVerifyJobPublisher faceVerifyJobPublisher;

    public CheckinService(EmployeeRepository employeeRepository,
                          AssignmentService assignmentService,
                          SiteRepository siteRepository,
                          ShiftRepository shiftRepository,
                          GeofenceRepository geofenceRepository,
                          CheckinRepository checkinRepository,
                          UserRoleRepository userRoleRepository,
                          SiteScopeService siteScopeService,
                          AttendanceSummaryService attendanceSummaryService,
                          FaceVerifyJobPublisher faceVerifyJobPublisher) {
        this.employeeRepository = employeeRepository;
        this.assignmentService = assignmentService;
        this.siteRepository = siteRepository;
        this.shiftRepository = shiftRepository;
        this.geofenceRepository = geofenceRepository;
        this.checkinRepository = checkinRepository;
        this.userRoleRepository = userRoleRepository;
        this.siteScopeService = siteScopeService;
        this.attendanceSummaryService = attendanceSummaryService;
        this.faceVerifyJobPublisher = faceVerifyJobPublisher;
    }

    // ── Task 67: Available sites ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AvailableSiteResponse> getAvailableSites(UUID tenantId, UUID callerUserId) {
        Employee employee = resolveEmployee(tenantId, callerUserId);
        if (!"active".equals(employee.getStatus())) {
            // Inactive/terminated employees have nothing to check into — an empty list (not an
            // error) since this is a passive query, not an attempted action.
            return List.of();
        }

        // Resolved per-assignment in the SITE's own timezone (not the server's default zone),
        // including overnight shifts that started site-local "yesterday" and are still open —
        // see AssignmentService.resolveAvailableAssignmentsNow.
        List<AssignmentService.AssignmentAvailability> availabilities =
                assignmentService.resolveAvailableAssignmentsNow(tenantId, employee.getId());

        log.info("Available sites query: tenantId={} employeeId={} found={}",
                tenantId, employee.getId(), availabilities.size());

        return availabilities.stream()
                .map(av -> buildAvailableSiteResponse(av, tenantId))
                .toList();
    }

    // ── Task 68: Submit GPS check-in ─────────────────────────────────────────

    @Transactional
    public CheckinResponse submitCheckin(UUID tenantId, SubmitCheckinRequest request, UUID callerUserId) {
        Employee employee = resolveEmployee(tenantId, callerUserId);
        if (!"active".equals(employee.getStatus())) {
            throw new BusinessException(
                    "EMPLOYEE_NOT_ACTIVE",
                    "Tài khoản nhân viên hiện không hoạt động, không thể chấm công.",
                    HttpStatus.FORBIDDEN,
                    "Employee status is '" + employee.getStatus() + "', not active");
        }

        Site site = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(request.getSiteId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found: " + request.getSiteId()));
        if (!"active".equals(site.getStatus())) {
            throw new BusinessException(
                    "SITE_INACTIVE",
                    "Địa điểm này hiện không hoạt động, không thể chấm công.",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Site status is '" + site.getStatus() + "', not active");
        }

        // Same site-timezone-aware resolution used by getAvailableSites, so the App can never
        // see a site listed as available and then have submitCheckin disagree about it.
        AssignmentService.AssignmentAvailability availability = assignmentService
                .resolveAvailableAssignmentForSiteNow(tenantId, employee.getId(), request.getSiteId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active assignment found for this employee at site " + request.getSiteId() + " today"));
        Assignment assignment = availability.assignment();

        // Prevent a duplicate open check-in for this EMPLOYEE — across any assignment/site, not
        // just the one being checked into now, since an employee cannot physically be checked in
        // at two places at once (P0-3). A unique partial index (V73) backs this at the DB level
        // too, for the race window between this check and the insert below.
        checkinRepository.findByTenantIdAndEmployeeIdAndCheckOutAtIsNullAndDeletedAtIsNull(
                        tenantId, employee.getId())
                .ifPresent(existing -> {
                    throw new DuplicateResourceException(
                            "Employee already has an open check-in at site " + existing.getSiteId()
                                    + " (checked in at " + existing.getCheckInAt() + "). Please check out first.");
                });

        // Task 71: Early/late check-in window validation, using the same site-local instants
        // already resolved above. Also fetched here (once) to snapshot its time-affecting fields
        // onto the CheckinRecord being created — see the field-level comment on CheckinRecord for
        // why this must never be re-fetched live afterward.
        validateCheckinWindow(availability);
        Shift resolvedShift = assignment.getShiftId() != null
                ? shiftRepository.findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(
                        assignment.getShiftId(), site.getId(), tenantId).orElse(null)
                : null;

        // Geofence validation via PostGIS
        boolean insideGeofence = true;
        Optional<Geofence> geofenceOpt = geofenceRepository
                .findBySiteIdAndStatusAndDeletedAtIsNull(request.getSiteId(), "active");

        if (geofenceOpt.isPresent()) {
            Geofence geofence = geofenceOpt.get();
            String polygonWkt = toWkt(geofence.getCoordinates());
            insideGeofence = checkinRepository.isPointWithinBufferedPolygon(
                    request.getLongitude(), request.getLatitude(),
                    polygonWkt, geofence.getBufferMeters());
        }

        double riskScore = computeRiskScore(request.getGpsAccuracy(), insideGeofence);
        String status = insideGeofence ? "valid" : "pending_review";

        CheckinRecord record = CheckinRecord.builder()
                .tenantId(tenantId)
                .siteId(request.getSiteId())
                .employeeId(employee.getId())
                .assignmentId(assignment.getId())
                .shiftId(assignment.getShiftId())
                .shiftStartTime(resolvedShift != null ? resolvedShift.getStartTime() : null)
                .shiftEndTime(resolvedShift != null ? resolvedShift.getEndTime() : null)
                .shiftAllowOvernight(resolvedShift != null ? resolvedShift.isAllowOvernight() : null)
                .shiftAllowOvertime(resolvedShift != null ? resolvedShift.isAllowOvertime() : null)
                .shiftLateCheckoutMinutes(resolvedShift != null ? resolvedShift.getLateCheckoutMinutes() : null)
                .status(status)
                .checkInAt(OffsetDateTime.now())
                .checkInLat(request.getLatitude())
                .checkInLon(request.getLongitude())
                .checkInAccuracy(request.getGpsAccuracy())
                .checkInInsideGeofence(insideGeofence)
                .gpsRiskScore(riskScore)
                .deviceId(request.getDeviceId())
                .build();

        try {
            checkinRepository.save(record);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Belt-and-suspenders for the race window on the in-service duplicate check above:
            // the V73 partial unique index rejects a second concurrent open session for this
            // employee at the DB level even if two requests passed the check almost simultaneously.
            throw new DuplicateResourceException(
                    "Employee already has an open check-in. Please check out first.");
        }
        log.info("Check-in recorded: id={} employeeId={} siteId={} status={} insideGeofence={} riskScore={}",
                record.getId(), employee.getId(), request.getSiteId(), status, insideGeofence, riskScore);

        // Task 69/70: async face verification if photo provided
        if (request.getEmployeePhotoBase64() != null && !request.getEmployeePhotoBase64().isBlank()) {
            try {
                faceVerifyJobPublisher.publish(tenantId, employee.getId(), record.getId(),
                        "checkin", request.getEmployeePhotoBase64(), request.isRequiresLiveness());
            } catch (Exception e) {
                log.warn("Failed to publish face verify job for checkin {}: {}", record.getId(), e.getMessage());
            }
        }

        // Task 80: update daily attendance summary on check-in (marks any prior summary as incomplete)
        try {
            attendanceSummaryService.recomputeForCheckin(record);
        } catch (Exception e) {
            log.warn("Failed to update attendance summary for checkin {}: {}", record.getId(), e.getMessage());
        }

        return toCheckinResponse(record);
    }

    // ── Task 72: Submit GPS check-out ────────────────────────────────────────

    @Transactional
    public CheckinResponse submitCheckout(UUID tenantId, UUID checkinId,
                                          SubmitCheckoutRequest request, UUID callerUserId) {
        Employee employee = resolveEmployee(tenantId, callerUserId);

        CheckinRecord record = checkinRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(checkinId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Check-in record not found: " + checkinId));

        if (!record.getEmployeeId().equals(employee.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Check-in record does not belong to this employee");
        }

        if (record.getCheckOutAt() != null) {
            throw new DuplicateResourceException(
                    "Already checked out at " + record.getCheckOutAt());
        }

        // Geofence validation for checkout location
        boolean insideGeofence = true;
        Optional<Geofence> geofenceOpt = geofenceRepository
                .findBySiteIdAndStatusAndDeletedAtIsNull(record.getSiteId(), "active");

        if (geofenceOpt.isPresent()) {
            Geofence geofence = geofenceOpt.get();
            String polygonWkt = toWkt(geofence.getCoordinates());
            insideGeofence = checkinRepository.isPointWithinBufferedPolygon(
                    request.getLongitude(), request.getLatitude(),
                    polygonWkt, geofence.getBufferMeters());
        }

        OffsetDateTime checkOutAt = OffsetDateTime.now();

        // Task 73: apply late-checkout cap using the shift snapshot captured at check-in time
        // (never a live re-fetch — see CheckinRecord field comment) so a Shift edit made after
        // this employee already checked in can never change how this session's hours are counted.
        String siteTimezone = siteRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(record.getSiteId(), tenantId)
                .map(Site::getTimezone)
                .orElse("UTC");
        int workMinutes = computeWorkMinutes(record.getCheckInAt(), checkOutAt,
                record.getShiftStartTime(), record.getShiftEndTime(),
                record.getShiftAllowOvernight(), record.getShiftAllowOvertime(),
                record.getShiftLateCheckoutMinutes(), siteTimezone);

        // Escalate to pending_review if checkout is outside geofence
        if (!insideGeofence && "valid".equals(record.getStatus())) {
            record.setStatus("pending_review");
        }

        record.setCheckOutAt(checkOutAt);
        record.setCheckOutLat(request.getLatitude());
        record.setCheckOutLon(request.getLongitude());
        record.setCheckOutAccuracy(request.getGpsAccuracy());
        record.setCheckOutInsideGeofence(insideGeofence);
        record.setWorkMinutes(workMinutes);

        checkinRepository.save(record);
        log.info("Check-out recorded: id={} employeeId={} siteId={} workMinutes={} insideGeofence={}",
                record.getId(), employee.getId(), record.getSiteId(), workMinutes, insideGeofence);

        // Task 80: update daily attendance summary after every checkout
        try {
            attendanceSummaryService.recomputeForCheckin(record);
        } catch (Exception e) {
            log.warn("Failed to update attendance summary for checkin {}: {}", record.getId(), e.getMessage());
        }

        return toCheckinResponse(record);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Employee resolveEmployee(UUID tenantId, UUID userId) {
        return employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No employee profile found for this user in tenant: " + tenantId));
    }

    /**
     * Tasks 73+74: Compute the final work_minutes for a check-in/checkout pair.
     *
     * Rules applied (all times resolved in site timezone):
     *   1. Effective start = max(checkInAt, shiftStart on checkInDate)
     *      — time spent in the early-check-in window before the shift starts does not count.
     *   2. Effective end (late-checkout cap, task 73):
     *      — allowOvertime=true  → cap at shiftEnd + lateCheckoutMinutes
     *      — allowOvertime=false → cap at shiftEnd (no overtime counted at all)
     *   3. workMinutes = max(0, duration(effectiveStart, effectiveEnd))
     *
     * When no shift is linked, raw duration (checkInAt → checkOutAt) is returned.
     * Handles overnight shifts (endTime on checkInDate + 1 day).
     *
     * Takes the shift's time-affecting fields directly (snapshotted onto the CheckinRecord at
     * check-in — see the field comment there) rather than a live {@code Shift} lookup, so a
     * Shift edited after this employee already checked in cannot change this computation.
     */
    private int computeWorkMinutes(OffsetDateTime checkInAt, OffsetDateTime checkOutAt,
                                    LocalTime shiftStartTime, LocalTime shiftEndTime,
                                    Boolean shiftAllowOvernight, Boolean shiftAllowOvertime,
                                    Integer shiftLateCheckoutMinutes, String siteTimezone) {
        if (shiftStartTime == null || shiftEndTime == null) {
            return Math.max(0, (int) Duration.between(checkInAt, checkOutAt).toMinutes());
        }

        boolean allowOvernight = Boolean.TRUE.equals(shiftAllowOvernight);
        boolean allowOvertime = Boolean.TRUE.equals(shiftAllowOvertime);
        int lateCheckoutMinutes = shiftLateCheckoutMinutes != null ? shiftLateCheckoutMinutes : 0;

        ZoneId zone = ZoneId.of(siteTimezone);
        LocalDate checkInDate = checkInAt.atZoneSameInstant(zone).toLocalDate();
        LocalDate endDate = allowOvernight ? checkInDate.plusDays(1) : checkInDate;

        // Effective start: earliest payable moment is when the shift actually begins.
        ZonedDateTime shiftStart = ZonedDateTime.of(checkInDate, shiftStartTime, zone);
        ZonedDateTime effectiveStart = checkInAt.atZoneSameInstant(zone).isBefore(shiftStart)
                ? shiftStart : checkInAt.atZoneSameInstant(zone);

        // Effective end: cap depends on whether overtime is counted.
        ZonedDateTime shiftEnd = ZonedDateTime.of(endDate, shiftEndTime, zone);
        ZonedDateTime effectiveEnd = allowOvertime
                ? shiftEnd.plusMinutes(lateCheckoutMinutes)
                : shiftEnd;
        ZonedDateTime checkOutZdt = checkOutAt.atZoneSameInstant(zone);
        if (checkOutZdt.isBefore(effectiveEnd)) {
            effectiveEnd = checkOutZdt;
        }

        int minutes = (int) Duration.between(effectiveStart, effectiveEnd).toMinutes();
        if (minutes < (int) Duration.between(checkInAt, checkOutAt).toMinutes()) {
            log.info("work_minutes adjusted: raw={}min final={}min (effectiveStart={} effectiveEnd={})",
                    (int) Duration.between(checkInAt, checkOutAt).toMinutes(), minutes,
                    effectiveStart, effectiveEnd);
        }
        return Math.max(0, minutes);
    }

    /**
     * Task 71 + P1-2: reject check-in if "now" (a real Instant, compared against instants
     * already resolved in the assignment's site timezone by
     * AssignmentService.resolveAvailableAssignmentsNow) falls before the shift's allowed
     * early check-in window (shiftStart - earlyCheckinMinutes) OR at/after shift end — a shift
     * that has already ended is not something you can newly check into. A shift-less assignment
     * (checkinAllowedFrom/shiftStartInstant null) has no time restriction.
     */
    private void validateCheckinWindow(AssignmentService.AssignmentAvailability availability) {
        if (availability.shiftStartInstant() == null) {
            return;
        }
        Instant now = Instant.now();
        ZoneId zone = availability.siteZone();

        if (now.isBefore(availability.checkinAllowedFrom())) {
            LocalTime shiftStartLocal = availability.shiftStartInstant().atZone(zone).toLocalTime();
            LocalTime allowedFromLocal = availability.checkinAllowedFrom().atZone(zone).toLocalTime();
            throw new BusinessException(
                    "CHECKIN_TOO_EARLY",
                    "Bạn đang chấm công quá sớm. Ca làm việc bắt đầu lúc " + shiftStartLocal
                            + ". Vui lòng đợi đến " + allowedFromLocal + " (" + zone + ") để chấm công.",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Too early to check in. Shift starts at " + shiftStartLocal
                            + ". Check-in allowed from " + allowedFromLocal + " (site timezone: " + zone + ")");
        }
        if (!now.isBefore(availability.checkinAllowedUntil())) {
            LocalTime shiftEndLocal = availability.shiftEndInstant().atZone(zone).toLocalTime();
            throw new BusinessException(
                    "CHECKIN_TOO_LATE",
                    "Ca làm việc đã kết thúc lúc " + shiftEndLocal + " (" + zone + "). Không thể chấm công cho ca đã qua.",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Too late to check in. Shift ended at " + shiftEndLocal + " (site timezone: " + zone + ")");
        }
    }

    /** Convert List<[lon, lat]> coordinates to WKT POLYGON string for PostGIS. */
    private String toWkt(List<List<Double>> coordinates) {
        List<Double> first = coordinates.get(0);
        List<Double> last = coordinates.get(coordinates.size() - 1);

        String points = coordinates.stream()
                .map(c -> c.get(0) + " " + c.get(1))
                .collect(Collectors.joining(", "));

        // WKT polygon must be closed (first == last point)
        boolean closed = first.get(0).equals(last.get(0)) && first.get(1).equals(last.get(1));
        if (!closed) {
            points += ", " + first.get(0) + " " + first.get(1);
        }

        return "POLYGON((" + points + "))";
    }

    /**
     * Basic GPS risk score (0.0 = low risk, 1.0 = high risk).
     * Higher accuracy numbers mean worse GPS quality.
     */
    private double computeRiskScore(Double gpsAccuracy, boolean insideGeofence) {
        double score = 0.0;
        if (gpsAccuracy != null) {
            if (gpsAccuracy > 100) score = 0.7;
            else if (gpsAccuracy > 50) score = 0.4;
            else if (gpsAccuracy > 20) score = 0.2;
        }
        if (!insideGeofence) {
            score = Math.min(1.0, score + 0.5);
        }
        return score;
    }

    // ── Task 76: Get check-in result ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public CheckinResponse getCheckinResult(UUID tenantId, UUID checkinId, UUID callerUserId) {
        Employee employee = resolveEmployee(tenantId, callerUserId);

        CheckinRecord record = checkinRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(checkinId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Check-in record not found: " + checkinId));

        if (!record.getEmployeeId().equals(employee.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Check-in record does not belong to this employee");
        }

        return toCheckinResponse(record);
    }

    // ── Task 77: Employee check-in history ───────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<CheckinResponse> getCheckinHistory(UUID tenantId, UUID callerUserId,
                                                            OffsetDateTime from, OffsetDateTime to,
                                                            int page, int size) {
        Employee employee = resolveEmployee(tenantId, callerUserId);

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "checkInAt"));
        Specification<CheckinRecord> spec =
                CheckinSpecification.build(tenantId, employee.getId(), null, null, from, to);
        Page<CheckinResponse> resultPage = checkinRepository.findAll(spec, pageable)
                .map(this::toCheckinResponse);

        log.info("Check-in history: tenantId={} employeeId={} from={} to={} total={}",
                tenantId, employee.getId(), from, to, resultPage.getTotalElements());

        return PageResponse.from(resultPage);
    }

    // ── Task 78: HR check-in list ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<CheckinResponse> listCheckins(UUID tenantId,
                                                       UUID employeeId, UUID siteId, String status,
                                                       OffsetDateTime from, OffsetDateTime to,
                                                       String sortBy, String sortDir,
                                                       int page, int size,
                                                       UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("checkins:list")) {
                throw new AccessDeniedException("You do not have permission to list check-ins in this tenant");
            }
        }

        Optional<Set<UUID>> allowedSiteIds =
                siteScopeService.resolveAllowedSiteIds(callerUserId, tenantId, callerIsPlatformAdmin);
        UUID effectiveSiteFilter = siteId;
        if (allowedSiteIds.isPresent()) {
            if (siteId != null) {
                // Caller explicitly asked for one site — only honor it if it's actually
                // within their allowed set, otherwise they'd see another site's data.
                if (!allowedSiteIds.get().contains(siteId)) {
                    throw new AccessDeniedException("You do not have permission to view check-ins for this site");
                }
            } else if (allowedSiteIds.get().size() == 1) {
                // No explicit siteId requested and the caller is scoped to exactly one site —
                // filter to it directly (CheckinSpecification only takes a single siteId).
                effectiveSiteFilter = allowedSiteIds.get().iterator().next();
            } else if (allowedSiteIds.get().isEmpty()) {
                return PageResponse.from(Page.empty(PageRequest.of(page, size)));
            } else {
                // Scoped to multiple sites with no explicit siteId requested — listCheckins'
                // underlying CheckinSpecification only supports a single siteId filter today,
                // so a multi-site supervisor must page through one site at a time via the
                // siteId query param instead of getting a merged multi-site view.
                throw new AccessDeniedException(
                        "You are scoped to multiple sites — pass a specific siteId to list its check-ins");
            }
        }

        String resolvedSort = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "checkInAt";
        Sort.Direction dir = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageable = PageRequest.of(page, size, Sort.by(dir, resolvedSort));

        Specification<CheckinRecord> spec =
                CheckinSpecification.build(tenantId, employeeId, effectiveSiteFilter, status, from, to);

        Page<CheckinResponse> resultPage = checkinRepository.findAll(spec, pageable)
                .map(this::toCheckinResponse);

        log.info("HR checkin list: tenantId={} employeeId={} siteId={} status={} total={}",
                tenantId, employeeId, siteId, status, resultPage.getTotalElements());

        return PageResponse.from(resultPage);
    }

    // ── Task 113: Employee submit explanation for check-in ───────────────────────

    @Transactional
    public com.fams.shared.dto.ExplanationResponse explainCheckin(UUID tenantId, UUID checkinId,
                                                                    com.fams.shared.dto.SubmitExplanationRequest request,
                                                                    UUID callerUserId) {
        Employee employee = resolveEmployee(tenantId, callerUserId);

        CheckinRecord record = checkinRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(checkinId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Check-in record not found: " + checkinId));

        if (!record.getEmployeeId().equals(employee.getId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Check-in record does not belong to this employee");
        }

        record.setEmployeeNote(request.getNote());
        record.setEmployeePhotoUrl(request.getPhotoUrl());
        checkinRepository.save(record);

        log.info("Employee explanation submitted: checkinId={} employeeId={}", checkinId, employee.getId());

        return com.fams.shared.dto.ExplanationResponse.builder()
                .id(record.getId())
                .employeeNote(record.getEmployeeNote())
                .employeePhotoUrl(record.getEmployeePhotoUrl())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    // ── Task 111: HR override check-in ──────────────────────────────────────────

    @Transactional
    public CheckinResponse overrideCheckin(UUID tenantId, UUID checkinId,
                                            OverrideCheckinRequest request,
                                            UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("checkins:list")) {
                throw new AccessDeniedException("You do not have permission to override check-ins in this tenant");
            }
        }

        CheckinRecord record = checkinRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(checkinId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Check-in record not found: " + checkinId));

        if (!siteScopeService.isSiteAllowed(callerUserId, tenantId, record.getSiteId(), callerIsPlatformAdmin)) {
            throw new AccessDeniedException("You do not have permission to override check-ins for this site");
        }

        if (record.getStatus().equals(request.getStatus())) {
            throw new IllegalStateException(
                    "Check-in is already in status '" + request.getStatus() + "' — no change needed");
        }

        record.setStatus(request.getStatus());
        record.setNote(request.getReason());
        checkinRepository.save(record);

        log.info("HR override checkin: checkinId={} newStatus={} by={}", checkinId, request.getStatus(), callerUserId);

        attendanceSummaryService.recomputeForCheckin(record);

        return toCheckinResponse(record);
    }

    // ── Task 79: HR check-in detail ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public CheckinDetailResponse getCheckinDetail(UUID tenantId, UUID checkinId,
                                                   UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("checkins:list")) {
                throw new AccessDeniedException("You do not have permission to view check-in details in this tenant");
            }
        }

        CheckinRecord record = checkinRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(checkinId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Check-in record not found: " + checkinId));

        if (!siteScopeService.isSiteAllowed(callerUserId, tenantId, record.getSiteId(), callerIsPlatformAdmin)) {
            throw new AccessDeniedException("You do not have permission to view check-in details for this site");
        }

        Employee employee = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(record.getEmployeeId(), tenantId)
                .orElse(null);

        Site site = siteRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(record.getSiteId(), tenantId)
                .orElse(null);

        com.fams.modules.shift.entity.Shift shift = null;
        if (record.getShiftId() != null && site != null) {
            shift = shiftRepository
                    .findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(
                            record.getShiftId(), site.getId(), tenantId)
                    .orElse(null);
        }

        return toCheckinDetailResponse(record, employee, site, shift);
    }

    private CheckinDetailResponse toCheckinDetailResponse(CheckinRecord r, Employee employee,
                                                            Site site, com.fams.modules.shift.entity.Shift shift) {
        CheckinDetailResponse.EmployeeInfo empInfo = employee == null ? null :
                CheckinDetailResponse.EmployeeInfo.builder()
                        .id(employee.getId())
                        .firstName(employee.getFirstName())
                        .lastName(employee.getLastName())
                        .employeeCode(employee.getEmployeeCode())
                        .position(employee.getPosition())
                        .department(employee.getDepartment())
                        .build();

        CheckinDetailResponse.SiteInfo siteInfo = site == null ? null :
                CheckinDetailResponse.SiteInfo.builder()
                        .id(site.getId())
                        .name(site.getName())
                        .code(site.getCode())
                        .address(site.getAddress())
                        .latitude(site.getLatitude())
                        .longitude(site.getLongitude())
                        .timezone(site.getTimezone())
                        .build();

        CheckinDetailResponse.ShiftInfo shiftInfo = shift == null ? null :
                CheckinDetailResponse.ShiftInfo.builder()
                        .id(shift.getId())
                        .name(shift.getName())
                        .startTime(shift.getStartTime())
                        .endTime(shift.getEndTime())
                        .allowOvernight(shift.isAllowOvernight())
                        .earlyCheckinMinutes(shift.getEarlyCheckinMinutes())
                        .lateCheckoutMinutes(shift.getLateCheckoutMinutes())
                        .build();

        return CheckinDetailResponse.builder()
                .id(r.getId())
                .tenantId(r.getTenantId())
                .status(r.getStatus())
                .message(resolveDisplayMessage(r))
                .gpsRiskScore(r.getGpsRiskScore())
                .deviceId(r.getDeviceId())
                .checkInAt(r.getCheckInAt())
                .checkInLat(r.getCheckInLat())
                .checkInLon(r.getCheckInLon())
                .checkInAccuracy(r.getCheckInAccuracy())
                .checkInInsideGeofence(r.isCheckInInsideGeofence())
                .checkOutAt(r.getCheckOutAt())
                .checkOutLat(r.getCheckOutLat())
                .checkOutLon(r.getCheckOutLon())
                .checkOutAccuracy(r.getCheckOutAccuracy())
                .checkOutInsideGeofence(r.getCheckOutInsideGeofence())
                .workMinutes(r.getWorkMinutes())
                .employee(empInfo)
                .site(siteInfo)
                .shift(shiftInfo)
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }

    public CheckinResponse toCheckinResponse(CheckinRecord r) {
        return CheckinResponse.builder()
                .id(r.getId())
                .tenantId(r.getTenantId())
                .siteId(r.getSiteId())
                .employeeId(r.getEmployeeId())
                .assignmentId(r.getAssignmentId())
                .shiftId(r.getShiftId())
                .status(r.getStatus())
                .checkInAt(r.getCheckInAt())
                .checkInLat(r.getCheckInLat())
                .checkInLon(r.getCheckInLon())
                .checkInAccuracy(r.getCheckInAccuracy())
                .checkInInsideGeofence(r.isCheckInInsideGeofence())
                .checkOutAt(r.getCheckOutAt())
                .workMinutes(r.getWorkMinutes())
                .gpsRiskScore(r.getGpsRiskScore())
                .deviceId(r.getDeviceId())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .message(resolveDisplayMessage(r))
                .faceVerified(r.getFaceVerified())
                .livenessVerified(r.getLivenessVerified())
                .faceVerifyScore(r.getFaceVerifyScore())
                .build();
    }

    private String resolveDisplayMessage(CheckinRecord r) {
        boolean checkedOut = r.getCheckOutAt() != null;
        return switch (r.getStatus()) {
            case "valid" -> checkedOut
                    ? "Check-out recorded successfully. You worked " + r.getWorkMinutes() + " minutes."
                    : "Check-in recorded successfully.";
            case "pending_review" -> checkedOut
                    ? "Check-out recorded. Your location was outside the site geofence — HR will review your attendance."
                    : "Check-in recorded. Your location was outside the site geofence — HR will review your attendance.";
            case "rejected" -> "Your attendance record was rejected by HR. Please contact your manager.";
            default -> "Attendance recorded with status: " + r.getStatus() + ".";
        };
    }

    // ── Available sites helpers ───────────────────────────────────────────────

    private AvailableSiteResponse buildAvailableSiteResponse(
            AssignmentService.AssignmentAvailability availability, UUID tenantId) {
        Assignment assignment = availability.assignment();
        Site site = siteRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(assignment.getSiteId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Site not found: " + assignment.getSiteId()));

        AvailableSiteResponse.ShiftInfo shiftInfo = null;
        if (assignment.getShiftId() != null) {
            shiftInfo = shiftRepository
                    .findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(
                            assignment.getShiftId(), site.getId(), tenantId)
                    .map(this::toShiftInfo)
                    .orElse(null);
        }

        AvailableSiteResponse.GeofenceInfo geofenceInfo = geofenceRepository
                .findBySiteIdAndStatusAndDeletedAtIsNull(site.getId(), "active")
                .map(this::toGeofenceInfo)
                .orElse(null);

        ZoneId zone = availability.siteZone();
        Instant now = Instant.now();
        String availabilityStatus;
        if (availability.shiftStartInstant() == null) {
            availabilityStatus = "unrestricted";
        } else if (now.isBefore(availability.checkinAllowedFrom())) {
            availabilityStatus = "upcoming";
        } else if (now.isBefore(availability.checkinAllowedUntil())) {
            availabilityStatus = "open";
        } else {
            availabilityStatus = "closed";
        }

        return AvailableSiteResponse.builder()
                .assignmentId(assignment.getId())
                .assignmentRole(assignment.getRole())
                .site(toSiteInfo(site))
                .shift(shiftInfo)
                .geofence(geofenceInfo)
                .serverNow(now.atZone(zone).toOffsetDateTime())
                .checkinAllowedFrom(availability.checkinAllowedFrom() != null
                        ? availability.checkinAllowedFrom().atZone(zone).toOffsetDateTime() : null)
                .checkinAllowedUntil(availability.checkinAllowedUntil() != null
                        ? availability.checkinAllowedUntil().atZone(zone).toOffsetDateTime() : null)
                .availabilityStatus(availabilityStatus)
                .build();
    }

    private AvailableSiteResponse.SiteInfo toSiteInfo(Site site) {
        return AvailableSiteResponse.SiteInfo.builder()
                .id(site.getId())
                .name(site.getName())
                .code(site.getCode())
                .address(site.getAddress())
                .latitude(site.getLatitude())
                .longitude(site.getLongitude())
                .timezone(site.getTimezone())
                .build();
    }

    private AvailableSiteResponse.ShiftInfo toShiftInfo(Shift shift) {
        return AvailableSiteResponse.ShiftInfo.builder()
                .id(shift.getId())
                .name(shift.getName())
                .startTime(shift.getStartTime())
                .endTime(shift.getEndTime())
                .allowOvernight(shift.isAllowOvernight())
                .earlyCheckinMinutes(shift.getEarlyCheckinMinutes())
                .lateCheckoutMinutes(shift.getLateCheckoutMinutes())
                .build();
    }

    private AvailableSiteResponse.GeofenceInfo toGeofenceInfo(Geofence geofence) {
        return AvailableSiteResponse.GeofenceInfo.builder()
                .id(geofence.getId())
                .coordinates(geofence.getCoordinates())
                .bufferMeters(geofence.getBufferMeters())
                .build();
    }
}
