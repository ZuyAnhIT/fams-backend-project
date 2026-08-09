package com.fams.modules.checkin.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.assignment.util.DayOfWeekBitmask;
import com.fams.modules.attendance.service.AttendanceSummaryService;
import com.fams.modules.checkin.dto.request.OfflineCheckinRequest;
import com.fams.modules.checkin.dto.response.SyncResultItem;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.geofence.entity.Geofence;
import com.fams.modules.geofence.repository.GeofenceRepository;
import com.fams.modules.shift.entity.Shift;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.shared.ai.FaceVerifyJobPublisher;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Offline check-in sync — same business rules as {@link CheckinService#submitCheckin}, applied
 * to a PAST timestamp instead of "now". This is a genuinely separate code path (not a thin
 * wrapper around submitCheckin) because a past, already-recorded moment can't go through
 * "now"-relative resolution like {@code AssignmentService.resolveAvailableAssignmentForSiteNow}
 * or a live active-liveness challenge — but every rule that CAN apply retroactively (employee/
 * site active, day-of-week + early-checkin window, duplicate-open-session, Face ID policy,
 * shift-time snapshot) must still be enforced here. This file was originally written before
 * several rounds of hardening landed in submitCheckin and silently fell behind every time —
 * keep that in mind if submitCheckin gains new business rules later: check whether this needs
 * the same treatment.
 */
@Slf4j
@Service
public class OfflineSyncService {

    private final EmployeeRepository employeeRepository;
    private final AssignmentRepository assignmentRepository;
    private final SiteRepository siteRepository;
    private final ShiftRepository shiftRepository;
    private final GeofenceRepository geofenceRepository;
    private final CheckinRepository checkinRepository;
    private final FaceProfileRepository faceProfileRepository;
    private final AttendanceSummaryService attendanceSummaryService;
    private final FaceVerifyJobPublisher faceVerifyJobPublisher;

    public OfflineSyncService(EmployeeRepository employeeRepository,
                               AssignmentRepository assignmentRepository,
                               SiteRepository siteRepository,
                               ShiftRepository shiftRepository,
                               GeofenceRepository geofenceRepository,
                               CheckinRepository checkinRepository,
                               FaceProfileRepository faceProfileRepository,
                               AttendanceSummaryService attendanceSummaryService,
                               FaceVerifyJobPublisher faceVerifyJobPublisher) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.siteRepository = siteRepository;
        this.shiftRepository = shiftRepository;
        this.geofenceRepository = geofenceRepository;
        this.checkinRepository = checkinRepository;
        this.faceProfileRepository = faceProfileRepository;
        this.attendanceSummaryService = attendanceSummaryService;
        this.faceVerifyJobPublisher = faceVerifyJobPublisher;
    }

    @Transactional
    public List<SyncResultItem> sync(UUID tenantId, List<OfflineCheckinRequest> requests, UUID callerUserId) {
        Employee employee = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No employee profile found for this user in tenant: " + tenantId));

        List<OfflineCheckinRequest> sorted = requests.stream()
                .sorted((a, b) -> a.getCheckinAt().compareTo(b.getCheckinAt()))
                .collect(Collectors.toList());

        List<SyncResultItem> results = new ArrayList<>();
        for (OfflineCheckinRequest req : sorted) {
            results.add(processSingle(tenantId, employee, req));
        }
        return results;
    }

    private SyncResultItem processSingle(UUID tenantId, Employee employee, OfflineCheckinRequest req) {
        // Idempotency: reject duplicate nonce with the existing record
        Optional<CheckinRecord> existing = checkinRepository
                .findByEmployeeIdAndClientNonceAndDeletedAtIsNull(employee.getId(), req.getClientNonce());
        if (existing.isPresent()) {
            return SyncResultItem.builder()
                    .clientNonce(req.getClientNonce())
                    .status("accepted")
                    .reason("duplicate nonce — previously accepted")
                    .checkinRecordId(existing.get().getId())
                    .build();
        }

        // An offline device could easily be carrying a stale session for an employee who was
        // terminated/deactivated after they last synced — same rule as the online path.
        if (!"active".equals(employee.getStatus())) {
            return reject(req.getClientNonce(),
                    "Employee status is '" + employee.getStatus() + "', not active");
        }

        // Validate assignment belongs to this employee and tenant
        Assignment assignment = assignmentRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(req.getAssignmentId(), tenantId)
                .orElse(null);

        if (assignment == null) {
            return reject(req.getClientNonce(), "Assignment not found: " + req.getAssignmentId());
        }
        if (!assignment.getEmployeeId().equals(employee.getId())) {
            return reject(req.getClientNonce(), "Assignment does not belong to this employee");
        }
        if (!"active".equals(assignment.getStatus())) {
            return reject(req.getClientNonce(), "Assignment is not active");
        }

        Site site = siteRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(assignment.getSiteId(), tenantId)
                .orElse(null);
        if (site == null) {
            return reject(req.getClientNonce(), "Site not found: " + assignment.getSiteId());
        }
        if (!"active".equals(site.getStatus())) {
            return reject(req.getClientNonce(), "Site status is '" + site.getStatus() + "', not active");
        }

        // Validate assignment covers the offline checkin date AND day-of-week — the online path
        // (findActiveAssignmentsForEmployeeOnDate) checks both; this used to only check the date
        // range, silently accepting a checkin on a day-of-week the assignment doesn't cover.
        //
        // Must use the SITE's timezone, not UTC — a UTC-derived calendar date fed into
        // ZonedDateTime.of(checkinDate, ..., siteZone) below silently shifts the effective day by
        // the site's UTC offset. For Vietnam (UTC+7), any checkin between 00:00-06:59 local time
        // landed on the WRONG UTC calendar day, which could wrongly reject a valid day-of-week or
        // assignment-period checkin near a boundary. Found via App team integration testing.
        ZoneId siteZone = ZoneId.of(site.getTimezone());
        LocalDate checkinDate = req.getCheckinAt().atZoneSameInstant(siteZone).toLocalDate();
        if (checkinDate.isBefore(assignment.getStartDate())
                || (assignment.getEndDate() != null && checkinDate.isAfter(assignment.getEndDate()))) {
            return reject(req.getClientNonce(), "Check-in date " + checkinDate + " is outside assignment period");
        }
        Set<java.time.DayOfWeek> allowedDays = DayOfWeekBitmask.fromBitmask(assignment.getDaysOfWeek());
        if (allowedDays != null && !allowedDays.contains(checkinDate.getDayOfWeek())) {
            return reject(req.getClientNonce(),
                    "Assignment does not cover " + checkinDate.getDayOfWeek() + " (checkin date " + checkinDate + ")");
        }

        Shift shift = assignment.getShiftId() != null
                ? shiftRepository.findByIdAndSiteIdAndTenantIdAndDeletedAtIsNull(
                        assignment.getShiftId(), site.getId(), tenantId).orElse(null)
                : null;

        // Early-checkin window (Task 71's rule) re-derived for the historical timestamp — a
        // device that was "offline" still can't backdate an implausibly-early checkin once it
        // finally syncs.
        if (shift != null) {
            ZonedDateTime shiftStart = ZonedDateTime.of(checkinDate, shift.getStartTime(), siteZone);
            ZonedDateTime allowedFrom = shiftStart.minusMinutes(shift.getEarlyCheckinMinutes());
            if (req.getCheckinAt().atZoneSameInstant(siteZone).isBefore(allowedFrom)) {
                return reject(req.getClientNonce(),
                        "Check-in at " + req.getCheckinAt() + " is earlier than the shift's allowed window "
                                + "(from " + allowedFrom + ")");
            }
        }

        // Face ID / active-liveness policy — enforced best-effort, since active liveness cannot
        // be proven retroactively for an already-elapsed offline moment (there is no "now" to
        // challenge against). gps_face accepts a plain photo as sufficient (same as the online
        // fallback path); gps_face_liveness cannot be satisfied at all offline, so it is always
        // escalated to pending_review below rather than silently trusted as 'valid'.
        String policy = resolveEffectiveCheckinPolicy(site, shift);
        boolean hasPhoto = req.getFacePhotoBase64() != null && !req.getFacePhotoBase64().isBlank();
        boolean faceRequired = !"gps_only".equals(policy);
        if (faceRequired) {
            boolean hasEnrolledFace = faceProfileRepository.findByEmployeeIdAndTenantId(employee.getId(), tenantId)
                    .map(p -> "enrolled".equals(p.getStatus()))
                    .orElse(false);
            if (!hasEnrolledFace) {
                return reject(req.getClientNonce(),
                        "Employee has no approved Face ID enrollment, required by this site/shift's policy");
            }
            if (!hasPhoto) {
                return reject(req.getClientNonce(),
                        "This site/shift requires Face ID verification — a photo must be included "
                                + "even for an offline check-in");
            }
        }

        // Conflict: existing server-side record overlaps the same assignment + time window
        Optional<CheckinRecord> conflict = checkinRepository
                .findOverlappingSession(req.getAssignmentId(), req.getCheckinAt());
        if (conflict.isPresent()) {
            return SyncResultItem.builder()
                    .clientNonce(req.getClientNonce())
                    .status("conflict")
                    .reason("A check-in record already exists for this assignment at the given time")
                    .checkinRecordId(conflict.get().getId())
                    .build();
        }

        // Geofence check
        boolean insideGeofence = true;
        Optional<Geofence> geofenceOpt = geofenceRepository
                .findBySiteIdAndStatusAndDeletedAtIsNull(site.getId(), "active");
        if (geofenceOpt.isPresent()) {
            Geofence geofence = geofenceOpt.get();
            String polygonWkt = toWkt(geofence.getCoordinates());
            insideGeofence = checkinRepository.isPointWithinBufferedPolygon(
                    req.getLon(), req.getLat(), polygonWkt, geofence.getBufferMeters());
        }

        double riskScore = computeRiskScore(req.getAccuracy(), insideGeofence);
        String status = insideGeofence ? "valid" : "pending_review";
        // gps_face_liveness can never be truly proven for a past offline moment (see comment
        // above) — flag for HR review even when geofence/photo both look fine, instead of
        // silently accepting the weaker "photo only" proof as if it met the strongest tier.
        if ("gps_face_liveness".equals(policy) && "valid".equals(status)) {
            status = "pending_review";
        }

        CheckinRecord record = CheckinRecord.builder()
                .tenantId(tenantId)
                .siteId(assignment.getSiteId())
                .employeeId(employee.getId())
                .assignmentId(assignment.getId())
                .shiftId(assignment.getShiftId())
                // Same snapshot-at-time-of-action principle as the online path (V72) — without
                // this, a later checkout's work_minutes calculation would silently fall back to
                // "no shift" behavior (raw duration, no caps) for every offline-synced session.
                .shiftStartTime(shift != null ? shift.getStartTime() : null)
                .shiftEndTime(shift != null ? shift.getEndTime() : null)
                .shiftAllowOvernight(shift != null ? shift.isAllowOvernight() : null)
                .shiftAllowOvertime(shift != null ? shift.isAllowOvertime() : null)
                .shiftLateCheckoutMinutes(shift != null ? shift.getLateCheckoutMinutes() : null)
                .shiftMaxOtMinutesPerDay(shift != null ? shift.getMaxOtMinutesPerDay() : null)
                .shiftMaxOtMinutesPerWeek(shift != null ? shift.getMaxOtMinutesPerWeek() : null)
                .status(status)
                .checkInAt(req.getCheckinAt())
                .checkInLat(req.getLat())
                .checkInLon(req.getLon())
                .checkInAccuracy(req.getAccuracy())
                .checkInInsideGeofence(insideGeofence)
                .gpsRiskScore(riskScore)
                .clientNonce(req.getClientNonce())
                .effectiveCheckinPolicy(policy)
                .source("offline")
                .build();

        try {
            checkinRepository.save(record);
        } catch (DataIntegrityViolationException e) {
            // The V73 partial unique index (one open session per employee) can fire here too —
            // e.g. this employee already has an open session from the online path, or from an
            // earlier item in this same offline batch that hasn't been checked out. Previously
            // unhandled, this would throw mid-batch and abort every remaining item in the sync
            // call instead of gracefully reporting just this one as a conflict.
            return SyncResultItem.builder()
                    .clientNonce(req.getClientNonce())
                    .status("conflict")
                    .reason("Employee already has another open check-in session")
                    .checkinRecordId(null)
                    .build();
        }
        log.info("Offline sync accepted: nonce={} employeeId={} assignmentId={} status={}",
                req.getClientNonce(), employee.getId(), req.getAssignmentId(), status);

        if (hasPhoto) {
            try {
                faceVerifyJobPublisher.publish(tenantId, employee.getId(), record.getId(),
                        "checkin", req.getFacePhotoBase64(), false);
            } catch (Exception e) {
                log.warn("Failed to publish face verify job for offline checkin {}: {}", record.getId(), e.getMessage());
            }
        }

        try {
            attendanceSummaryService.recomputeForCheckin(record);
        } catch (Exception e) {
            log.warn("Failed to update attendance summary for offline checkin {}: {}", record.getId(), e.getMessage());
        }

        return SyncResultItem.builder()
                .clientNonce(req.getClientNonce())
                .status("accepted")
                .reason(null)
                .checkinRecordId(record.getId())
                .build();
    }

    private String resolveEffectiveCheckinPolicy(Site site, Shift shift) {
        if (shift != null && shift.getCheckinPolicyOverride() != null) {
            return shift.getCheckinPolicyOverride();
        }
        return site.getCheckinPolicy();
    }

    private SyncResultItem reject(UUID clientNonce, String reason) {
        return SyncResultItem.builder()
                .clientNonce(clientNonce)
                .status("rejected")
                .reason(reason)
                .checkinRecordId(null)
                .build();
    }

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

    private String toWkt(List<List<Double>> coordinates) {
        List<Double> first = coordinates.get(0);
        List<Double> last = coordinates.get(coordinates.size() - 1);
        String points = coordinates.stream()
                .map(c -> c.get(0) + " " + c.get(1))
                .collect(Collectors.joining(", "));
        boolean closed = first.get(0).equals(last.get(0)) && first.get(1).equals(last.get(1));
        if (!closed) {
            points += ", " + first.get(0) + " " + first.get(1);
        }
        return "POLYGON((" + points + "))";
    }
}
