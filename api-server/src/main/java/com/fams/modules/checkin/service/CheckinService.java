package com.fams.modules.checkin.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.service.AssignmentService;
import com.fams.modules.attendance.service.AttendanceSummaryService;
import com.fams.modules.audit.service.AuditLogService;
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
import com.fams.modules.employee.entity.LivenessChallenge;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.employee.repository.LivenessChallengeRepository;
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
import com.fams.shared.security.HttpRequestUtils;
import com.fams.shared.storage.ExplanationEvidenceStorageService;
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
import org.springframework.web.multipart.MultipartFile;

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
    private static final int CHECKIN_CHALLENGE_FRESHNESS_MINUTES = 2;

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
    private final FaceProfileRepository faceProfileRepository;
    private final LivenessChallengeRepository livenessChallengeRepository;
    private final ExplanationEvidenceStorageService evidenceStorageService;
    private final AuditLogService auditLogService;

    public CheckinService(EmployeeRepository employeeRepository,
                          AssignmentService assignmentService,
                          SiteRepository siteRepository,
                          ShiftRepository shiftRepository,
                          GeofenceRepository geofenceRepository,
                          CheckinRepository checkinRepository,
                          UserRoleRepository userRoleRepository,
                          SiteScopeService siteScopeService,
                          AttendanceSummaryService attendanceSummaryService,
                          FaceVerifyJobPublisher faceVerifyJobPublisher,
                          FaceProfileRepository faceProfileRepository,
                          LivenessChallengeRepository livenessChallengeRepository,
                          ExplanationEvidenceStorageService evidenceStorageService,
                          AuditLogService auditLogService) {
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
        this.faceProfileRepository = faceProfileRepository;
        this.livenessChallengeRepository = livenessChallengeRepository;
        this.evidenceStorageService = evidenceStorageService;
        this.auditLogService = auditLogService;
    }

    private void recordAudit(UUID tenantId, UUID actorId, UUID checkinId, String action,
                              java.util.Map<String, Object> newValue) {
        try {
            auditLogService.record(
                    tenantId, actorId, null,
                    "Checkin", checkinId.toString(), action,
                    null, newValue,
                    HttpRequestUtils.currentRequestId(),
                    HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception e) {
            log.warn("Failed to record audit log action={} checkinId={}: {}", action, checkinId, e.getMessage());
        }
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

        // Site policy (with the resolved Shift's optional override) decides how strict
        // verification must be — see resolveEffectiveCheckinPolicy/enforceCheckinPolicy for the
        // 3 tiers. Only known once the shift is resolved above, hence checked here rather than
        // up front. A challenge that later FAILS the async embedding match is not blocked
        // synchronously (the AI call is async) — instead FaceResultCallbackController escalates
        // this check-in to pending_review + creates a violation once the result comes back,
        // mirroring how the random-check module already treats face_fail/liveness_fail.
        String effectivePolicy = resolveEffectiveCheckinPolicy(site, resolvedShift);
        LivenessChallenge checkinChallenge = enforceCheckinPolicy(effectivePolicy, "checkin", request.getSiteId(),
                employee.getId(), tenantId, request.getEmployeePhotoBase64(), request.getLivenessChallengeId());

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
                .shiftGraceMinutes(resolvedShift != null ? resolvedShift.getGraceMinutes() : null)
                .shiftMaxOtMinutesPerDay(resolvedShift != null ? resolvedShift.getMaxOtMinutesPerDay() : null)
                .shiftMaxOtMinutesPerWeek(resolvedShift != null ? resolvedShift.getMaxOtMinutesPerWeek() : null)
                .status(status)
                .checkInAt(OffsetDateTime.now())
                .checkInLat(request.getLatitude())
                .checkInLon(request.getLongitude())
                .checkInAccuracy(request.getGpsAccuracy())
                .checkInInsideGeofence(insideGeofence)
                .gpsRiskScore(riskScore)
                .deviceId(request.getDeviceId())
                .effectiveCheckinPolicy(effectivePolicy)
                .source("online")
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
        recordAudit(tenantId, callerUserId, record.getId(), "checkin_submitted", java.util.Map.of(
                "siteId", record.getSiteId(),
                "employeeId", record.getEmployeeId(),
                "shiftId", record.getShiftId() != null ? record.getShiftId() : "",
                "status", status,
                "insideGeofence", insideGeofence,
                "gpsRiskScore", riskScore));

        // Task 69/70 + active liveness: async face verification. A Face-ID-required site uses
        // the already-verified challenge's stored frame (liveness already proven via the
        // head-pose/blink sequence, so requiresLiveness is redundant there) — the challenge
        // itself was already atomically consumed above, before this record was even built; a
        // non-required site falls back to the original optional single-photo path unchanged.
        if (checkinChallenge != null) {
            try {
                faceVerifyJobPublisher.publishFromChallenge(tenantId, employee.getId(), record.getId(),
                        "checkin", checkinChallenge.getId());
            } catch (Exception e) {
                // At a Face-ID-required site, "couldn't even ask the AI worker to check" must
                // not silently leave the checkin looking fully valid — escalate to pending_review
                // (not a violation: this is an infra failure, not a proven face mismatch).
                log.warn("Failed to publish face verify job from challenge for checkin {}: {}",
                        record.getId(), e.getMessage());
                // Column-scoped conditional update (not a full save(record)) — see the repository
                // method's javadoc for why: this must not race-clobber a checkout that could be
                // landing on the same row concurrently.
                if (checkinRepository.escalateToPendingReviewIfValid(record.getId()) > 0) {
                    record.setStatus("pending_review");
                }
            }
        } else if (request.getEmployeePhotoBase64() != null && !request.getEmployeePhotoBase64().isBlank()) {
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

        return toCheckinResponse(record, employee, site);
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

        Site site = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(record.getSiteId(), tenantId)
                .orElse(null);
        Shift shift = record.getShiftId() != null && site != null
                ? shiftRepository.findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(
                        record.getShiftId(), site.getId(), tenantId).orElse(null)
                : null;

        // Checkout is verified against the SAME tier that applied at check-in — using the
        // snapshot captured on the record then (V78), not a live re-resolve. If HR changes a
        // site/shift's policy mid-shift, an employee already checked in under the old policy
        // must not get stranded at checkout unable to satisfy a requirement they were never
        // told about (same "snapshot at time of action" principle as the shift-time fields,
        // V72). This does NOT weaken buddy-checkout prevention: that protection comes from
        // "checkout requires the same proof check-in required", not from always tracking the
        // latest policy value. Records predating V78 have no snapshot — fall back to a live
        // resolve for those only.
        String effectivePolicy = record.getEffectiveCheckinPolicy() != null
                ? record.getEffectiveCheckinPolicy()
                : (site != null ? resolveEffectiveCheckinPolicy(site, shift) : "gps_only");
        LivenessChallenge checkoutChallenge = enforceCheckinPolicy(effectivePolicy, "checkout", record.getSiteId(),
                employee.getId(), tenantId, request.getEmployeePhotoBase64(), request.getLivenessChallengeId());

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
        String siteTimezone = site != null ? site.getTimezone() : "UTC";
        int workMinutes = computeWorkMinutes(record.getCheckInAt(), checkOutAt,
                record.getShiftStartTime(), record.getShiftEndTime(),
                record.getShiftAllowOvernight(), record.getShiftAllowOvertime(),
                record.getShiftLateCheckoutMinutes(), siteTimezone);

        // Column-scoped update (not a full save(record)) — a check-in/checkout face-verify
        // callback for this SAME row can legitimately arrive and commit around the same instant
        // (that race is exactly what caused an earlier check-out's checkOutAt to be observed
        // reverted to NULL during testing). Writing only the checkout-specific columns here means
        // a concurrent callback writing only ITS columns can never clobber this write or vice
        // versa, regardless of which transaction commits first. See CheckinRepository javadoc.
        checkinRepository.applyCheckout(record.getId(), checkOutAt, request.getLatitude(), request.getLongitude(),
                request.getGpsAccuracy(), insideGeofence, workMinutes);
        record.setCheckOutAt(checkOutAt);
        record.setCheckOutLat(request.getLatitude());
        record.setCheckOutLon(request.getLongitude());
        record.setCheckOutAccuracy(request.getGpsAccuracy());
        record.setCheckOutInsideGeofence(insideGeofence);
        record.setWorkMinutes(workMinutes);

        // Escalate to pending_review if checkout is outside geofence — separate, idempotent,
        // WHERE-guarded statement (see escalateToPendingReviewIfValid javadoc).
        if (!insideGeofence && checkinRepository.escalateToPendingReviewIfValid(record.getId()) > 0) {
            record.setStatus("pending_review");
        }

        log.info("Check-out recorded: id={} employeeId={} siteId={} workMinutes={} insideGeofence={}",
                record.getId(), employee.getId(), record.getSiteId(), workMinutes, insideGeofence);
        recordAudit(tenantId, callerUserId, record.getId(), "checkout_submitted", java.util.Map.of(
                "siteId", record.getSiteId(),
                "employeeId", record.getEmployeeId(),
                "status", record.getStatus(),
                "insideGeofence", insideGeofence,
                "workMinutes", workMinutes));

        // Async face verification at checkout — mirrors check-in: challenge-sourced when the
        // policy demanded one, else the optional plain-photo path. FaceResultCallbackController
        // routes sourceType="checkout" results to the checkout_* columns (kept separate from the
        // check-in verification already on this same row) and escalates/creates a violation the
        // same way a failed REQUIRED check-in verification does.
        if (checkoutChallenge != null) {
            try {
                faceVerifyJobPublisher.publishFromChallenge(tenantId, employee.getId(), record.getId(),
                        "checkout", checkoutChallenge.getId());
            } catch (Exception e) {
                log.warn("Failed to publish face verify job from challenge for checkout {}: {}",
                        record.getId(), e.getMessage());
                if (checkinRepository.escalateToPendingReviewIfValid(record.getId()) > 0) {
                    record.setStatus("pending_review");
                }
            }
        } else if (request.getEmployeePhotoBase64() != null && !request.getEmployeePhotoBase64().isBlank()) {
            try {
                faceVerifyJobPublisher.publish(tenantId, employee.getId(), record.getId(),
                        "checkout", request.getEmployeePhotoBase64(), request.isRequiresLiveness());
            } catch (Exception e) {
                log.warn("Failed to publish face verify job for checkout {}: {}", record.getId(), e.getMessage());
            }
        }

        // Task 80: update daily attendance summary after every checkout
        try {
            attendanceSummaryService.recomputeForCheckin(record);
        } catch (Exception e) {
            log.warn("Failed to update attendance summary for checkin {}: {}", record.getId(), e.getMessage());
        }

        return toCheckinResponse(record, employee, site);
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

        Site site = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(record.getSiteId(), tenantId).orElse(null);
        return toCheckinResponse(record, employee, site);
    }

    // ── Task 77: Employee check-in history ───────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<CheckinResponse> getCheckinHistory(UUID tenantId, UUID callerUserId,
                                                            OffsetDateTime from, OffsetDateTime to,
                                                            int page, int size) {
        return getCheckinHistory(tenantId, callerUserId, null, null, from, to, page, size);
    }

    /** @param status found via audit (2026-08-02) — lets an employee filter their own history to
     *  status=pending_review, i.e. "which of my check-ins need an explanation right now" — the
     *  employee-facing half of the same "needs my attention" inbox as ViolationService
     *  #listMyViolations (the two are still separate lists, since a checkin and a violation are
     *  genuinely different objects, but both are now at least individually filterable/findable
     *  instead of the employee having to page through everything).
     *  @param siteId #77 gap fix (2026-08-17): an employee working multiple sites had no way to
     *  narrow their own history down to one — the spec already supported a siteId predicate
     *  (used by the HR-facing #78 listCheckins), it just wasn't wired into this employee-facing
     *  overload. Omit to see every site, same as before this fix. */
    @Transactional(readOnly = true)
    public PageResponse<CheckinResponse> getCheckinHistory(UUID tenantId, UUID callerUserId, UUID siteId,
                                                            String status,
                                                            OffsetDateTime from, OffsetDateTime to,
                                                            int page, int size) {
        Employee employee = resolveEmployee(tenantId, callerUserId);

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "checkInAt"));
        Specification<CheckinRecord> spec =
                CheckinSpecification.build(tenantId, employee.getId(), siteId, status, from, to);
        Page<CheckinRecord> resultPage = checkinRepository.findAll(spec, pageable);

        log.info("Check-in history: tenantId={} employeeId={} siteId={} from={} to={} total={}",
                tenantId, employee.getId(), siteId, from, to, resultPage.getTotalElements());

        return toPageResponse(resultPage, toCheckinResponsesBatch(resultPage.getContent()));
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

        Page<CheckinRecord> resultPage = checkinRepository.findAll(spec, pageable);

        log.info("HR checkin list: tenantId={} employeeId={} siteId={} status={} total={}",
                tenantId, employeeId, siteId, status, resultPage.getTotalElements());

        return toPageResponse(resultPage, toCheckinResponsesBatch(resultPage.getContent()));
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

        // getPhotoUrl() is @Deprecated on purpose — this check exists specifically to catch
        // clients still sending the old field and tell them to switch to multipart, not to
        // actually read the value.
        @SuppressWarnings("deprecation")
        String legacyPhotoUrl = request.getPhotoUrl();
        if (org.springframework.util.StringUtils.hasText(legacyPhotoUrl)) {
            throw new IllegalArgumentException(
                    "photoUrl is no longer accepted; upload photo using multipart/form-data");
        }
        record.setEmployeeNote(request.getNote().trim());
        // A note-only update must not silently remove previously uploaded private evidence.
        checkinRepository.save(record);

        log.info("Employee explanation submitted: checkinId={} employeeId={}", checkinId, employee.getId());

        return com.fams.shared.dto.ExplanationResponse.builder()
                .id(record.getId())
                .employeeNote(record.getEmployeeNote())
                .employeePhotoUrl(toEvidenceUrl(record))
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    @Transactional
    public com.fams.shared.dto.ExplanationResponse explainCheckinWithPhoto(
            UUID tenantId, UUID checkinId, String note, MultipartFile photo, UUID callerUserId) {
        Employee employee = resolveEmployee(tenantId, callerUserId);
        CheckinRecord record = checkinRepository.findByIdAndTenantIdAndDeletedAtIsNull(checkinId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Check-in record not found: " + checkinId));
        if (!record.getEmployeeId().equals(employee.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Check-in record does not belong to this employee");
        }
        if (!org.springframework.util.StringUtils.hasText(note)) {
            throw new IllegalArgumentException("note is required");
        }
        String marker = evidenceStorageService.store(tenantId, "checkin", checkinId, photo);
        record.setEmployeeNote(note.trim());
        record.setEmployeePhotoUrl(marker);
        checkinRepository.save(record);
        return com.fams.shared.dto.ExplanationResponse.builder()
                .id(record.getId())
                .employeeNote(record.getEmployeeNote())
                .employeePhotoUrl(toEvidenceUrl(record))
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public ExplanationEvidenceStorageService.StoredEvidence getExplanationEvidence(
            UUID tenantId, UUID checkinId, UUID callerUserId, boolean callerIsPlatformAdmin) {
        getCheckinDetail(tenantId, checkinId, callerUserId, callerIsPlatformAdmin);
        CheckinRecord record = checkinRepository.findByIdAndTenantIdAndDeletedAtIsNull(checkinId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Check-in record not found: " + checkinId));
        return evidenceStorageService.load(record.getEmployeePhotoUrl());
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

        String oldStatus = record.getStatus();
        record.setStatus(request.getStatus());
        record.setNote(request.getReason());
        record.setOverriddenBy(callerUserId);
        record.setOverriddenAt(OffsetDateTime.now());
        checkinRepository.save(record);

        log.info("HR override checkin: checkinId={} newStatus={} by={}", checkinId, request.getStatus(), callerUserId);
        recordAudit(tenantId, callerUserId, record.getId(), "checkin_overridden", java.util.Map.of(
                "oldStatus", oldStatus,
                "newStatus", request.getStatus(),
                "reason", request.getReason()));

        attendanceSummaryService.recomputeForCheckin(record);

        Employee employee = employeeRepository.findByIdAndTenantIdAndDeletedAtIsNull(record.getEmployeeId(), tenantId)
                .orElse(null);
        Site site = siteRepository.findByIdAndTenantIdAndDeletedAtIsNull(record.getSiteId(), tenantId).orElse(null);
        return toCheckinResponse(record, employee, site);
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
                .faceVerified(r.getFaceVerified())
                .livenessVerified(r.getLivenessVerified())
                .faceVerifyScore(r.getFaceVerifyScore())
                .checkoutFaceVerified(r.getCheckoutFaceVerified())
                .checkoutLivenessVerified(r.getCheckoutLivenessVerified())
                .checkoutFaceVerifyScore(r.getCheckoutFaceVerifyScore())
                .effectiveCheckinPolicy(r.getEffectiveCheckinPolicy())
                .source(r.getSource())
                .clientNonce(r.getClientNonce())
                .note(r.getNote())
                .overriddenBy(r.getOverriddenBy())
                .overriddenAt(r.getOverriddenAt())
                .employeeNote(r.getEmployeeNote())
                .employeePhotoUrl(toEvidenceUrl(r))
                .employee(empInfo)
                .site(siteInfo)
                .shift(shiftInfo)
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }

    private String toEvidenceUrl(CheckinRecord record) {
        String stored = record.getEmployeePhotoUrl();
        if (stored != null && stored.startsWith(ExplanationEvidenceStorageService.MARKER_PREFIX)) {
            return "/api/v1/tenants/" + record.getTenantId() + "/checkin/"
                    + record.getId() + "/explanation-photo";
        }
        return stored;
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
                .checkOutLat(r.getCheckOutLat())
                .checkOutLon(r.getCheckOutLon())
                .checkOutAccuracy(r.getCheckOutAccuracy())
                .checkOutInsideGeofence(r.getCheckOutInsideGeofence())
                .workMinutes(r.getWorkMinutes())
                .gpsRiskScore(r.getGpsRiskScore())
                .deviceId(r.getDeviceId())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .message(resolveDisplayMessage(r))
                .faceVerified(r.getFaceVerified())
                .livenessVerified(r.getLivenessVerified())
                .faceVerifyScore(r.getFaceVerifyScore())
                .checkoutFaceVerified(r.getCheckoutFaceVerified())
                .checkoutLivenessVerified(r.getCheckoutLivenessVerified())
                .checkoutFaceVerifyScore(r.getCheckoutFaceVerifyScore())
                .effectiveCheckinPolicy(r.getEffectiveCheckinPolicy())
                .source(r.getSource())
                .employeeNote(r.getEmployeeNote())
                .employeePhotoUrl(toEvidenceUrl(r))
                .build();
    }

    /** Same mapping, plus batch-resolved display names — avoids a client-side N+1 lookup for
     *  list/history screens (Web integration report, 2026-07-29). Employee/Site are optional
     *  (null if not resolved/found); names are simply left null in that case. */
    private CheckinResponse toCheckinResponse(CheckinRecord r, Employee employee, Site site) {
        CheckinResponse response = toCheckinResponse(r);
        if (employee != null) {
            response.setEmployeeName((employee.getFirstName() + " " + employee.getLastName()).trim());
            response.setEmployeeCode(employee.getEmployeeCode());
        }
        if (site != null) {
            response.setSiteName(site.getName());
        }
        return response;
    }

    /** Batch-fetches Employee/Site for a page of records in 2 queries total instead of N+1. */
    private List<CheckinResponse> toCheckinResponsesBatch(List<CheckinRecord> records) {
        Set<UUID> employeeIds = records.stream().map(CheckinRecord::getEmployeeId).collect(Collectors.toSet());
        Set<UUID> siteIds = records.stream().map(CheckinRecord::getSiteId).collect(Collectors.toSet());

        java.util.Map<UUID, Employee> employeesById = employeeIds.isEmpty() ? java.util.Map.of()
                : employeeRepository.findAllById(employeeIds).stream()
                        .collect(Collectors.toMap(Employee::getId, e -> e));
        java.util.Map<UUID, Site> sitesById = siteIds.isEmpty() ? java.util.Map.of()
                : siteRepository.findAllById(siteIds).stream()
                        .collect(Collectors.toMap(Site::getId, s -> s));

        return records.stream()
                .map(r -> toCheckinResponse(r, employeesById.get(r.getEmployeeId()), sitesById.get(r.getSiteId())))
                .toList();
    }

    /** Rebuilds a PageResponse from a JPA Page's pagination metadata plus a separately-mapped
     *  content list — needed because Page.map() only supports 1:1 mapping, not the batch
     *  employee/site lookup toCheckinResponsesBatch does. */
    private PageResponse<CheckinResponse> toPageResponse(Page<CheckinRecord> sourcePage, List<CheckinResponse> content) {
        return PageResponse.<CheckinResponse>builder()
                .content(content)
                .page(sourcePage.getNumber())
                .size(sourcePage.getSize())
                .totalElements(sourcePage.getTotalElements())
                .totalPages(sourcePage.getTotalPages())
                .first(sourcePage.isFirst())
                .last(sourcePage.isLast())
                .build();
    }

    // Vietnamese, matching every BusinessException.userMessage elsewhere in this service (audit
    // 2026-08-05) — this was the one message on the check-in flow still in English, which read
    // as inconsistent to the Vietnamese field workers this app is built for, right on the
    // success/pending path they see every single day (not just on error, like every other
    // friendly message here).
    private String resolveDisplayMessage(CheckinRecord r) {
        boolean checkedOut = r.getCheckOutAt() != null;
        return switch (r.getStatus()) {
            case "valid" -> checkedOut
                    ? "Chấm công ra thành công. Bạn đã làm việc " + r.getWorkMinutes() + " phút."
                    : "Chấm công vào thành công.";
            // pending_review can now come from more than just geofence (P0-3 async face-verify
            // escalation, offline-sync best-effort verification) — keep the message generic
            // rather than always blaming location, which would mislead someone whose actual
            // issue was a failed face match.
            case "pending_review" -> checkedOut
                    ? "Đã ghi nhận chấm công ra, nhưng cần HR xem lại (vị trí hoặc xác thực khuôn mặt). "
                            + "Việc này không ảnh hưởng tới lương cho tới khi được duyệt."
                    : "Đã ghi nhận chấm công vào, nhưng cần HR xem lại (vị trí hoặc xác thực khuôn mặt). "
                            + "Bạn có thể tiếp tục làm việc bình thường.";
            case "rejected" -> "Bản ghi chấm công của bạn đã bị HR từ chối. Vui lòng liên hệ quản lý.";
            default -> "Đã ghi nhận chấm công với trạng thái: " + r.getStatus() + ".";
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

        Shift resolvedShift = null;
        AvailableSiteResponse.ShiftInfo shiftInfo = null;
        if (assignment.getShiftId() != null) {
            resolvedShift = shiftRepository
                    .findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(
                            assignment.getShiftId(), site.getId(), tenantId)
                    .orElse(null);
            shiftInfo = resolvedShift != null ? toShiftInfo(resolvedShift) : null;
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
                .effectiveCheckinPolicy(resolveEffectiveCheckinPolicy(site, resolvedShift))
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

    /** A Shift's override wins if set; otherwise inherit the Site's policy. */
    private String resolveEffectiveCheckinPolicy(Site site, Shift shift) {
        if (shift != null && shift.getCheckinPolicyOverride() != null) {
            return shift.getCheckinPolicyOverride();
        }
        return site.getCheckinPolicy();
    }

    /**
     * Shared by submitCheckin and submitCheckout — validates whatever the client supplied
     * (photoBase64 and/or challengeId) actually satisfies the effective policy tier, atomically
     * consuming a challenge if one both applies and was used. Never trusts the client's own
     * choice of which proof to send; always re-derives what's REQUIRED from the policy itself.
     *
     * <ul>
     *   <li>gps_only — nothing required, returns null (caller's existing optional-photo path,
     *       if any, is untouched by this method).</li>
     *   <li>gps_face — requires an enrolled face, then EITHER a plain photo OR a passed challenge
     *       (the challenge is accepted too — it's the strictly stronger proof, and a client that
     *       already did the full active-liveness dance shouldn't be forced to also send a plain
     *       photo). Returns the challenge if one was used, else null (caller publishes from the
     *       plain photo itself).</li>
     *   <li>gps_face_liveness — requires an enrolled face AND a passed challenge specifically;
     *       a plain photo alone is rejected. Always returns the (now-consumed) challenge.</li>
     * </ul>
     *
     * @param purpose "checkin" or "checkout" — must match the challenge's own purpose column, so
     *                a check-in challenge can't be spent at checkout or vice versa.
     */
    private LivenessChallenge enforceCheckinPolicy(String policy, String purpose, UUID siteId,
                                                    UUID employeeId, UUID tenantId,
                                                    String photoBase64, UUID challengeId) {
        if ("gps_only".equals(policy)) {
            return null;
        }

        boolean hasEnrolledFace = faceProfileRepository.findByEmployeeIdAndTenantId(employeeId, tenantId)
                .map(p -> "enrolled".equals(p.getStatus()))
                .orElse(false);
        if (!hasEnrolledFace) {
            throw new BusinessException(
                    "FACE_ID_NOT_ENROLLED",
                    "Địa điểm/ca này yêu cầu Face ID nhưng bạn chưa đăng ký (hoặc chưa được duyệt). "
                            + "Vui lòng hoàn tất đăng ký Face ID trước.",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Employee has no approved Face ID enrollment, required by policy '" + policy + "'");
        }

        boolean challengeRequired = "gps_face_liveness".equals(policy);
        if (!challengeRequired && challengeId == null) {
            // gps_face tier satisfied via the simpler plain-photo path.
            if (photoBase64 == null || photoBase64.isBlank()) {
                throw new BusinessException(
                        "FACE_ID_REQUIRED",
                        "Cần xác thực khuôn mặt (ảnh chụp hoặc quay động) để tiếp tục.",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Policy '" + policy + "' requires a Face ID photo or an active-liveness challenge");
            }
            return null;
        }

        if (challengeId == null) {
            throw new BusinessException(
                    "FACE_ID_REQUIRED",
                    "Cần xác thực khuôn mặt chủ động (quay đầu/nháy mắt theo yêu cầu).",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Policy '" + policy + "' requires a passed active-liveness challenge");
        }
        LivenessChallenge challenge = livenessChallengeRepository
                .findByIdAndTenantIdAndEmployeeId(challengeId, tenantId, employeeId)
                .filter(c -> purpose.equals(c.getPurpose()) && "passed".equals(c.getStatus()))
                // Must have been started FOR this exact site — otherwise a challenge completed
                // at Site A could be carried over and spent at Site B.
                .filter(c -> siteId.equals(c.getSiteId()))
                // Must be fresh — a 'passed' challenge sitting unused for a long time is a
                // window for reuse far from where/when it was actually performed.
                .filter(c -> c.getCompletedAt() != null
                        && c.getCompletedAt().isAfter(OffsetDateTime.now().minusMinutes(CHECKIN_CHALLENGE_FRESHNESS_MINUTES)))
                .orElseThrow(() -> new BusinessException(
                        "FACE_ID_REQUIRED",
                        "Xác thực khuôn mặt chủ động không hợp lệ, không đúng địa điểm này, hoặc đã hết hạn. "
                                + "Vui lòng thực hiện lại ngay tại đây.",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "livenessChallengeId is not a passed, unconsumed, fresh '" + purpose + "' challenge "
                                + "for this employee at this exact site"));

        // Atomic claim, inside this same transaction as the checkin/checkout write: only
        // succeeds if the challenge is STILL 'passed' at this exact instant, so two concurrent
        // requests replaying the same challengeId can't both get through — the loser gets
        // rowcount=0 here and the whole transaction rolls back on the exception.
        if (livenessChallengeRepository.consumeIfPassed(challenge.getId()) == 0) {
            throw new DuplicateResourceException(
                    "This active-liveness challenge was already used by another request. Please perform a new one.");
        }
        return challenge;
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
