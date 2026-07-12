package com.fams.modules.checkin.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.attendance.service.AttendanceSummaryService;
import com.fams.modules.checkin.dto.request.OfflineCheckinRequest;
import com.fams.modules.checkin.dto.response.SyncResultItem;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.geofence.entity.Geofence;
import com.fams.modules.geofence.repository.GeofenceRepository;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.shared.ai.FaceVerifyJobPublisher;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OfflineSyncService {

    private final EmployeeRepository employeeRepository;
    private final AssignmentRepository assignmentRepository;
    private final SiteRepository siteRepository;
    private final ShiftRepository shiftRepository;
    private final GeofenceRepository geofenceRepository;
    private final CheckinRepository checkinRepository;
    private final AttendanceSummaryService attendanceSummaryService;
    private final FaceVerifyJobPublisher faceVerifyJobPublisher;

    public OfflineSyncService(EmployeeRepository employeeRepository,
                               AssignmentRepository assignmentRepository,
                               SiteRepository siteRepository,
                               ShiftRepository shiftRepository,
                               GeofenceRepository geofenceRepository,
                               CheckinRepository checkinRepository,
                               AttendanceSummaryService attendanceSummaryService,
                               FaceVerifyJobPublisher faceVerifyJobPublisher) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.siteRepository = siteRepository;
        this.shiftRepository = shiftRepository;
        this.geofenceRepository = geofenceRepository;
        this.checkinRepository = checkinRepository;
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

        // Validate assignment covers the offline checkin date
        LocalDate checkinDate = req.getCheckinAt().toLocalDate();
        if (checkinDate.isBefore(assignment.getStartDate())
                || (assignment.getEndDate() != null && checkinDate.isAfter(assignment.getEndDate()))) {
            return reject(req.getClientNonce(), "Check-in date " + checkinDate + " is outside assignment period");
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
        Site site = siteRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(assignment.getSiteId(), tenantId)
                .orElse(null);

        boolean insideGeofence = true;
        if (site != null) {
            Optional<Geofence> geofenceOpt = geofenceRepository
                    .findBySiteIdAndStatusAndDeletedAtIsNull(site.getId(), "active");
            if (geofenceOpt.isPresent()) {
                Geofence geofence = geofenceOpt.get();
                String polygonWkt = toWkt(geofence.getCoordinates());
                insideGeofence = checkinRepository.isPointWithinBufferedPolygon(
                        req.getLon(), req.getLat(), polygonWkt, geofence.getBufferMeters());
            }
        }

        double riskScore = computeRiskScore(req.getAccuracy(), insideGeofence);
        String status = insideGeofence ? "valid" : "pending_review";

        CheckinRecord record = CheckinRecord.builder()
                .tenantId(tenantId)
                .siteId(assignment.getSiteId())
                .employeeId(employee.getId())
                .assignmentId(assignment.getId())
                .shiftId(assignment.getShiftId())
                .status(status)
                .checkInAt(req.getCheckinAt())
                .checkInLat(req.getLat())
                .checkInLon(req.getLon())
                .checkInAccuracy(req.getAccuracy())
                .checkInInsideGeofence(insideGeofence)
                .gpsRiskScore(riskScore)
                .clientNonce(req.getClientNonce())
                .build();

        checkinRepository.save(record);
        log.info("Offline sync accepted: nonce={} employeeId={} assignmentId={} status={}",
                req.getClientNonce(), employee.getId(), req.getAssignmentId(), status);

        if (req.getFacePhotoBase64() != null && !req.getFacePhotoBase64().isBlank()) {
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
