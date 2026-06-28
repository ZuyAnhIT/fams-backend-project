package com.fams.modules.randomcheck.service;

import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.geofence.entity.Geofence;
import com.fams.modules.geofence.repository.GeofenceRepository;
import com.fams.modules.randomcheck.dto.request.SubmitCheckResponseRequest;
import com.fams.modules.randomcheck.dto.response.CheckResponseDto;
import com.fams.modules.randomcheck.entity.CheckResponse;
import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.repository.CheckResponseRepository;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.modules.violation.entity.Violation;
import com.fams.modules.violation.repository.ViolationRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CheckResponseService {

    private static final BigDecimal LIVENESS_THRESHOLD = new BigDecimal("0.7");

    private final ScheduledCheckRepository scheduledCheckRepository;
    private final CheckResponseRepository checkResponseRepository;
    private final GeofenceRepository geofenceRepository;
    private final CheckinRepository checkinRepository;
    private final ViolationRepository violationRepository;

    public CheckResponseService(ScheduledCheckRepository scheduledCheckRepository,
                                CheckResponseRepository checkResponseRepository,
                                GeofenceRepository geofenceRepository,
                                CheckinRepository checkinRepository,
                                ViolationRepository violationRepository) {
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.checkResponseRepository = checkResponseRepository;
        this.geofenceRepository = geofenceRepository;
        this.checkinRepository = checkinRepository;
        this.violationRepository = violationRepository;
    }

    /**
     * Submits a response for a scheduled check.
     *
     * Guards (in order):
     *  1. Check must exist and belong to the tenant + employee.
     *  2. Check must be in 'sent' status.
     *  3. Current time must not exceed expires_at (task 105).
     *  4. No duplicate response.
     *
     * Verification (task 107):
     *  - location_only: PostGIS geofence check
     *  - location_face: + face image present
     *  - location_face_liveness: + liveness score >= 0.7
     *
     * Creates a violation for each failed verification type.
     */
    @Transactional
    public CheckResponseDto submit(UUID tenantId, UUID checkId,
                                   UUID employeeId, SubmitCheckResponseRequest request) {

        ScheduledCheck check = scheduledCheckRepository.findByIdAndTenant(checkId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Scheduled check not found: " + checkId));

        if (!check.getEmployeeId().equals(employeeId)) {
            throw new ResourceNotFoundException("Scheduled check not found: " + checkId);
        }

        String status = check.getStatus();
        if ("pending".equals(status) || "cancelled".equals(status)) {
            throw new IllegalStateException("Check is not available for response (status: " + status + ")");
        }
        if ("responded".equals(status) || "no_response".equals(status)) {
            throw new IllegalStateException("Check has already been closed (status: " + status + ")");
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (now.isAfter(check.getExpiresAt())) {
            log.warn("Late response rejected: checkId={} employeeId={} expiresAt={} respondedAt={}",
                    checkId, employeeId, check.getExpiresAt(), now);
            throw new CheckExpiredException(
                    "Response window has expired. Check expired at " + check.getExpiresAt());
        }

        if (checkResponseRepository.existsByScheduledCheckId(checkId)) {
            throw new IllegalStateException("A response has already been recorded for this check");
        }

        String checkMode = extractCheckMode(check.getConfigSnapshot());

        // Location verification via PostGIS geofence
        boolean locationVerified = verifyLocation(check.getSiteId(),
                request.getLatitude().doubleValue(), request.getLongitude().doubleValue());

        // Face verification — required for location_face and location_face_liveness modes
        Boolean faceVerified = null;
        if ("location_face".equals(checkMode) || "location_face_liveness".equals(checkMode)) {
            faceVerified = request.getFaceImageUrl() != null && !request.getFaceImageUrl().isBlank();
        }

        // Liveness verification — required for location_face_liveness mode
        Boolean livenessVerified = null;
        if ("location_face_liveness".equals(checkMode)) {
            livenessVerified = request.getLivenessScore() != null
                    && request.getLivenessScore().compareTo(LIVENESS_THRESHOLD) >= 0;
        }

        // Collect failure reasons
        List<String> failures = new ArrayList<>();
        if (!locationVerified) failures.add("location_mismatch");
        if (Boolean.FALSE.equals(faceVerified)) failures.add("face_fail");
        if (Boolean.FALSE.equals(livenessVerified)) failures.add("liveness_fail");

        boolean passed = failures.isEmpty();
        String outcome = passed ? "pass" : "fail";
        String failureReason = passed ? null : String.join(",", failures);

        CheckResponse response = CheckResponse.builder()
                .tenantId(tenantId)
                .scheduledCheckId(checkId)
                .employeeId(employeeId)
                .respondedAt(now)
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .accuracyMeters(request.getAccuracyMeters())
                .faceImageUrl(request.getFaceImageUrl())
                .livenessScore(request.getLivenessScore())
                .locationVerified(locationVerified)
                .faceVerified(faceVerified)
                .livenessVerified(livenessVerified)
                .outcome(outcome)
                .failureReason(failureReason)
                .build();

        checkResponseRepository.save(response);

        check.setStatus("responded");
        scheduledCheckRepository.save(check);

        // Create one violation per failure type (task 107)
        if (!locationVerified) {
            createViolation(check, response.getId(), "location_fail",
                    "Location outside geofence during random check");
        }
        if (Boolean.FALSE.equals(faceVerified)) {
            createViolation(check, response.getId(), "face_fail",
                    "Face verification failed during random check");
        }
        if (Boolean.FALSE.equals(livenessVerified)) {
            createViolation(check, response.getId(), "liveness_fail",
                    "Liveness verification failed (score=" + request.getLivenessScore() + ")");
        }

        log.info("Check response submitted: checkId={} employeeId={} outcome={} failures={}",
                checkId, employeeId, outcome, failures);

        return toDto(response);
    }

    private boolean verifyLocation(UUID siteId, double lat, double lon) {
        Optional<Geofence> geofenceOpt =
                geofenceRepository.findBySiteIdAndStatusAndDeletedAtIsNull(siteId, "active");
        if (geofenceOpt.isEmpty()) {
            // No geofence configured — pass by default
            return true;
        }
        Geofence geofence = geofenceOpt.get();
        String polygonWkt = toWkt(geofence.getCoordinates());
        return checkinRepository.isPointWithinBufferedPolygon(
                lon, lat, polygonWkt, geofence.getBufferMeters());
    }

    private void createViolation(ScheduledCheck check, UUID checkResponseId,
                                  String violationType, String description) {
        Violation violation = Violation.builder()
                .tenantId(check.getTenantId())
                .employeeId(check.getEmployeeId())
                .siteId(check.getSiteId())
                .scheduledCheckId(check.getId())
                .checkResponseId(checkResponseId)
                .violationType(violationType)
                .checkDate(check.getCheckDate())
                .description(description)
                .resolved(false)
                .build();
        violationRepository.save(violation);
        log.info("{} violation created: checkId={} employeeId={}", violationType, check.getId(), check.getEmployeeId());
    }

    /** Extracts checkMode from the JSON config snapshot stored on the scheduled check. */
    private String extractCheckMode(String configSnapshot) {
        if (configSnapshot == null) return "location_only";
        // Pattern: "checkMode":"<value>"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"checkMode\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(configSnapshot);
        return m.find() ? m.group(1) : "location_only";
    }

    /** Convert List<[lon, lat]> coordinates to WKT POLYGON string for PostGIS. */
    private String toWkt(List<List<Double>> coordinates) {
        List<Double> first = coordinates.get(0);
        List<Double> last = coordinates.get(coordinates.size() - 1);
        String points = coordinates.stream()
                .map(c -> c.get(0) + " " + c.get(1))
                .collect(Collectors.joining(", "));
        boolean closed = first.get(0).equals(last.get(0)) && first.get(1).equals(last.get(1));
        if (!closed) points += ", " + first.get(0) + " " + first.get(1);
        return "POLYGON((" + points + "))";
    }

    private CheckResponseDto toDto(CheckResponse r) {
        return CheckResponseDto.builder()
                .id(r.getId())
                .scheduledCheckId(r.getScheduledCheckId())
                .employeeId(r.getEmployeeId())
                .respondedAt(r.getRespondedAt())
                .latitude(r.getLatitude())
                .longitude(r.getLongitude())
                .accuracyMeters(r.getAccuracyMeters())
                .locationVerified(r.isLocationVerified())
                .faceVerified(r.getFaceVerified())
                .livenessVerified(r.getLivenessVerified())
                .outcome(r.getOutcome())
                .failureReason(r.getFailureReason())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
