package com.fams.modules.employee.service;

import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.employee.dto.request.RejectFaceEnrollmentRequest;
import com.fams.modules.employee.dto.request.VerifyFaceRequest;
import com.fams.modules.employee.dto.response.FaceIdStatusDto;
import com.fams.modules.employee.dto.response.LivenessChallengeResponse;
import com.fams.modules.employee.dto.response.LivenessChallengeResultResponse;
import com.fams.modules.employee.dto.response.VerifyFaceResultResponse;
import com.fams.modules.employee.dto.response.VerifyFaceSubmitResponse;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.entity.FaceProfile;
import com.fams.modules.employee.entity.FaceVerifyRequest;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.employee.repository.FaceVerifyRequestRepository;
import com.fams.modules.employee.repository.LivenessChallengeRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.shared.ai.AiServiceClient;
import com.fams.shared.ai.FaceVerifyJobPublisher;
import com.fams.shared.exception.BusinessException;
import com.fams.shared.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

/**
 * Face ID state machine (see docs/api/face-id-management-api.md for the full design rationale):
 *
 * <pre>
 *   not_enrolled ──consent──▶ not_enrolled (consentGiven=true) ──enroll──▶ [reviewStatus=pending]
 *                                                                              │
 *                                                        approve ◀────────────┴────────────▶ reject
 *                                                           │                                   │
 *                                                           ▼                                   ▼
 *                                                        enrolled                    (status unchanged,
 *                                                           │                         reviewStatus=rejected)
 *                                                    re-enroll (any time)
 *                                                           │
 *                                                           ▼
 *                                                [reviewStatus=pending, status STILL enrolled
 *                                                 — old face keeps working during review]
 * </pre>
 *
 * `status` (not_enrolled|enrolled|revoked) always reflects the last APPROVED state and is what
 * gates checkin. `reviewStatus` (none|pending|rejected) tracks an in-flight submission
 * independently, so a re-enrollment under review never interrupts the employee's ability to
 * check in with their currently-approved face.
 *
 * Every biometric vector (embedding / pendingEmbedding) is written and read ONLY by fams-ai via
 * psycopg2 — this service never sees the raw vector, only re-fetches the row after each fams-ai
 * call to read back non-biometric columns fams-ai updated (review_status, timestamps, counts).
 * Do not "helpfully" set those columns from Java before/after the call — fams-ai is the sole
 * writer, and a stale in-memory save() here would silently clobber what fams-ai just wrote.
 */
@Slf4j
@Service
public class FaceIdService {

    private static final String SOURCE_TYPE_STANDALONE = "standalone_verify";
    private static final int RATE_LIMIT_MAX_ATTEMPTS = 5;
    private static final int RATE_LIMIT_WINDOW_MINUTES = 10;

    private final FaceProfileRepository faceProfileRepository;
    private final FaceVerifyRequestRepository faceVerifyRequestRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRoleRepository userRoleRepository;
    private final AssignmentRepository assignmentRepository;
    private final SiteScopeService siteScopeService;
    private final AiServiceClient aiServiceClient;
    private final FaceVerifyJobPublisher faceVerifyJobPublisher;
    private final LivenessChallengeRepository livenessChallengeRepository;
    private final EntityManager entityManager;
    private final int enrollMinPhotos;
    private final int enrollMaxPhotos;
    private final int verifyTimeoutSeconds;

    public FaceIdService(FaceProfileRepository faceProfileRepository,
                         FaceVerifyRequestRepository faceVerifyRequestRepository,
                         EmployeeRepository employeeRepository,
                         UserRoleRepository userRoleRepository,
                         AssignmentRepository assignmentRepository,
                         SiteScopeService siteScopeService,
                         AiServiceClient aiServiceClient,
                         FaceVerifyJobPublisher faceVerifyJobPublisher,
                         LivenessChallengeRepository livenessChallengeRepository,
                         EntityManager entityManager,
                         @Value("${app.ai.enroll-min-photos:3}") int enrollMinPhotos,
                         @Value("${app.ai.enroll-max-photos:5}") int enrollMaxPhotos,
                         @Value("${app.ai.verify-timeout-seconds:30}") int verifyTimeoutSeconds) {
        this.faceProfileRepository = faceProfileRepository;
        this.faceVerifyRequestRepository = faceVerifyRequestRepository;
        this.employeeRepository = employeeRepository;
        this.userRoleRepository = userRoleRepository;
        this.assignmentRepository = assignmentRepository;
        this.siteScopeService = siteScopeService;
        this.aiServiceClient = aiServiceClient;
        this.faceVerifyJobPublisher = faceVerifyJobPublisher;
        this.livenessChallengeRepository = livenessChallengeRepository;
        this.entityManager = entityManager;
        this.enrollMinPhotos = enrollMinPhotos;
        this.enrollMaxPhotos = enrollMaxPhotos;
        this.verifyTimeoutSeconds = verifyTimeoutSeconds;
    }

    /** Re-reads non-biometric columns that fams-ai just wrote via its OWN direct SQL connection
     *  mid-transaction (embedding/pending_embedding/status/review_status/timestamps). A plain
     *  repository re-query is NOT enough here: Hibernate's persistence-context identity map
     *  returns the SAME already-managed Java instance for a given PK within one transaction,
     *  ignoring the fresh row fams-ai just wrote — {@code entityManager.refresh} forces Hibernate
     *  to reload this specific instance's state from the DB instead. */
    private FaceProfile refreshed(FaceProfile profile) {
        entityManager.refresh(profile);
        return profile;
    }

    private void requireManageOrSelf(Employee employee, UUID tenantId, UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (callerIsPlatformAdmin) return;
        boolean isOwnEmployee = employee.getUserId() != null && employee.getUserId().equals(callerUserId);
        if (isOwnEmployee) return;
        Set<String> permissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
        if (!permissions.contains("face_id:manage")) {
            throw new AccessDeniedException("You do not have permission to manage Face ID for this employee");
        }
    }

    @Transactional
    public FaceIdStatusDto giveConsent(UUID tenantId, UUID employeeId,
                                       UUID callerUserId, boolean callerIsPlatformAdmin) {
        Employee employee = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        // Deliberately NOT the usual "self OR face_id:manage" pattern: biometric consent is
        // sensitive-personal-data consent (Nghị định 13/2023/NĐ-CP, GDPR Art.9) and must be
        // given by the data subject themselves — HR cannot manufacture it on an employee's
        // behalf, even with face_id:manage. HR can still assist with photo capture (enrollFace
        // below) once the employee has consented through their own account.
        boolean isOwnEmployee = employee.getUserId() != null && employee.getUserId().equals(callerUserId);
        if (!isOwnEmployee) {
            throw new AccessDeniedException(
                    "Face ID consent must be given by the employee themselves, not on their behalf");
        }

        FaceProfile profile = faceProfileRepository
                .findByEmployeeIdAndTenantId(employeeId, tenantId)
                .orElseGet(() -> FaceProfile.builder()
                        .tenantId(tenantId)
                        .employeeId(employeeId)
                        .consentGiven(false)
                        .status("not_enrolled")
                        .reviewStatus("none")
                        .build());

        if (!profile.isConsentGiven()) {
            profile.setConsentGiven(true);
            profile.setConsentGivenAt(OffsetDateTime.now());
        }

        FaceProfile saved = faceProfileRepository.save(profile);
        log.info("Face ID consent recorded tenantId={} employeeId={}", tenantId, employeeId);

        return toDto(saved);
    }

    /** HR-assisted enrollment only (e.g. capturing photos at a kiosk with the employee
     *  physically present) — a human supervising the capture is itself a meaningful anti-fraud
     *  control, so this simpler multi-photo path (no active-liveness challenge) is acceptable
     *  here. Self-service enrollment MUST go through {@link #enrollFaceFromChallenge} instead —
     *  enforced below, not just a convention — since nobody is present to vouch for a photo an
     *  employee uploads from their own device. */
    @Transactional
    public FaceIdStatusDto enrollFace(UUID tenantId, UUID employeeId,
                                      List<MultipartFile> photos,
                                      UUID callerUserId, boolean callerIsPlatformAdmin) {
        Employee employee = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        boolean isOwnEmployee = employee.getUserId() != null && employee.getUserId().equals(callerUserId);
        if (isOwnEmployee) {
            throw new IllegalStateException(
                    "Self-service enrollment requires the active-liveness challenge flow — "
                            + "call POST .../face-id/liveness-challenge instead of uploading photos directly");
        }
        requireManageOrSelf(employee, tenantId, callerUserId, callerIsPlatformAdmin);

        int n = photos.size();
        if (n < enrollMinPhotos || n > enrollMaxPhotos) {
            throw new IllegalArgumentException(
                    "Expected " + enrollMinPhotos + "-" + enrollMaxPhotos + " photos, got " + n);
        }

        FaceProfile profile = faceProfileRepository
                .findByEmployeeIdAndTenantId(employeeId, tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Consent not recorded — call POST /face-id/consent first"));

        if (!profile.isConsentGiven()) {
            throw new IllegalStateException("Employee has not given consent for Face ID enrollment");
        }
        if ("pending".equals(profile.getReviewStatus())) {
            throw new IllegalStateException(
                    "A previous submission is still awaiting HR review — wait for it to be approved or rejected "
                            + "before submitting a new batch");
        }

        // Never activates the profile directly — lands in pending_embedding for HR review.
        // See fams-ai's enroll.py for the anti-spoofing + same-person cross-check this now runs.
        aiServiceClient.enrollFace(tenantId, employeeId, photos);

        log.info("Face ID enrollment submitted for review tenantId={} employeeId={}", tenantId, employeeId);
        return toDto(refreshed(profile));
    }

    /** Starts an active-liveness challenge: server picks a random ordered sequence of actions
     *  (always 'center' first, then 2 random dynamic actions) the client must perform, one frame
     *  per action, submitted in order to {@link #submitLivenessChallengeFrames}. Random per
     *  session so a pre-recorded clip of a PAST challenge can't be replayed against a new one. */
    @Transactional
    public LivenessChallengeResponse startLivenessChallenge(UUID tenantId, UUID employeeId, String purpose,
                                                             UUID siteId,
                                                             UUID callerUserId, boolean callerIsPlatformAdmin) {
        Employee employee = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));
        requireManageOrSelf(employee, tenantId, callerUserId, callerIsPlatformAdmin);

        if (!"enroll".equals(purpose) && !"checkin".equals(purpose) && !"checkout".equals(purpose)) {
            throw new IllegalArgumentException("purpose must be 'enroll', 'checkin', or 'checkout'");
        }
        if (("checkin".equals(purpose) || "checkout".equals(purpose)) && siteId == null) {
            throw new IllegalArgumentException("siteId is required for purpose=checkin/checkout — the "
                    + "resulting challenge can only be consumed by a check-in/check-out at that exact site");
        }

        // Never let the server start capturing/analyzing biometric photos for a self-enrollment
        // before consent is on record — POST /enroll(-from-challenge) already checks this, but by
        // then fams-ai has already processed (detected faces, computed embeddings on) whatever
        // frames were submitted. Blocking at the earliest possible point (before a challengeId
        // even exists) means an unconsented employee can never reach POST .../frames at all.
        if ("enroll".equals(purpose)) {
            boolean consented = faceProfileRepository.findByEmployeeIdAndTenantId(employeeId, tenantId)
                    .map(FaceProfile::isConsentGiven)
                    .orElse(false);
            if (!consented) {
                throw new IllegalStateException(
                        "Consent not recorded — call POST /face-id/consent before starting an enrollment challenge");
            }
        }

        // Rate-limit: caps repeated liveness attempts (brute-forcing anti-spoofing/pose
        // thresholds, or just hammering the AI service) independent of pass/fail outcome.
        OffsetDateTime windowStart = OffsetDateTime.now().minusMinutes(RATE_LIMIT_WINDOW_MINUTES);
        long recentAttempts = livenessChallengeRepository
                .countByTenantIdAndEmployeeIdAndCreatedAtAfter(tenantId, employeeId, windowStart);
        if (recentAttempts >= RATE_LIMIT_MAX_ATTEMPTS) {
            throw new BusinessException(
                    "TOO_MANY_ATTEMPTS",
                    "Bạn đã thử xác thực khuôn mặt quá nhiều lần. Vui lòng thử lại sau "
                            + RATE_LIMIT_WINDOW_MINUTES + " phút.",
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "Employee " + employeeId + " exceeded " + RATE_LIMIT_MAX_ATTEMPTS
                            + " liveness challenge attempts in " + RATE_LIMIT_WINDOW_MINUTES + " minutes");
        }

        Map<String, Object> result = aiServiceClient.startLivenessChallenge(tenantId, employeeId, purpose, siteId);
        @SuppressWarnings("unchecked")
        List<String> actions = (List<String>) result.get("actions");
        return LivenessChallengeResponse.builder()
                .challengeId(UUID.fromString((String) result.get("challengeId")))
                .actions(actions)
                .expiresAt(OffsetDateTime.parse((String) result.get("expiresAt")))
                .build();
    }

    /** Submits the recorded frames (one per action, in order) for a challenge. On success, the
     *  challenge is marked 'passed' and its verified embedding stays server-side, ready to be
     *  consumed by {@link #enrollFaceFromChallenge} (purpose=enroll) or by submitCheckin
     *  (purpose=checkin, see CheckinService) — the client never receives or re-uploads the
     *  embedding itself. */
    @Transactional
    public LivenessChallengeResultResponse submitLivenessChallengeFrames(
            UUID tenantId, UUID employeeId, UUID challengeId, List<MultipartFile> frames,
            UUID callerUserId, boolean callerIsPlatformAdmin) {
        Employee employee = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));
        requireManageOrSelf(employee, tenantId, callerUserId, callerIsPlatformAdmin);

        Map<String, Object> result = aiServiceClient.submitLivenessChallengeFrames(
                tenantId, employeeId, challengeId, frames);
        return LivenessChallengeResultResponse.builder()
                .status((String) result.get("status"))
                .reason((String) result.get("reason"))
                .steps(result.get("steps"))
                .build();
    }

    /** Self-service enrollment path — see {@link #enrollFace} for why this is mandatory when the
     *  employee is enrolling their own face rather than being enrolled by HR at a kiosk. */
    @Transactional
    public FaceIdStatusDto enrollFaceFromChallenge(UUID tenantId, UUID employeeId, UUID challengeId,
                                                   UUID callerUserId, boolean callerIsPlatformAdmin) {
        Employee employee = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));
        requireManageOrSelf(employee, tenantId, callerUserId, callerIsPlatformAdmin);

        FaceProfile profile = faceProfileRepository
                .findByEmployeeIdAndTenantId(employeeId, tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Consent not recorded — call POST /face-id/consent first"));
        if (!profile.isConsentGiven()) {
            throw new IllegalStateException("Employee has not given consent for Face ID enrollment");
        }
        if ("pending".equals(profile.getReviewStatus())) {
            throw new IllegalStateException(
                    "A previous submission is still awaiting HR review — wait for it to be approved or rejected "
                            + "before submitting a new one");
        }

        aiServiceClient.enrollFromChallenge(tenantId, employeeId, challengeId);

        log.info("Face ID enrollment (active liveness) submitted for review tenantId={} employeeId={}",
                tenantId, employeeId);
        return toDto(refreshed(profile));
    }

    @Transactional
    public FaceIdStatusDto approveEnrollment(UUID tenantId, UUID employeeId,
                                             UUID callerUserId, boolean callerIsPlatformAdmin) {
        // Existence + tenant-scope guard only — the fetched Employee itself isn't needed below.
        employeeRepository.findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        // Deliberately NOT self-callable: approving your OWN enrollment defeats the entire
        // point of the review step. Must be a distinct HR/Admin with face_id:manage.
        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("face_id:manage")) {
                throw new AccessDeniedException("You do not have permission to review Face ID enrollments");
            }
        }

        FaceProfile profile = faceProfileRepository
                .findByEmployeeIdAndTenantId(employeeId, tenantId)
                .filter(p -> "pending".equals(p.getReviewStatus()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No pending Face ID submission for employee: " + employeeId));

        aiServiceClient.approveFace(tenantId, employeeId);

        refreshed(profile);
        profile.setReviewedBy(callerUserId);
        profile.setReviewedAt(OffsetDateTime.now());
        FaceProfile saved = faceProfileRepository.save(profile);
        log.info("Face ID enrollment approved tenantId={} employeeId={} by={}", tenantId, employeeId, callerUserId);

        return toDto(saved);
    }

    @Transactional
    public FaceIdStatusDto rejectEnrollment(UUID tenantId, UUID employeeId,
                                            RejectFaceEnrollmentRequest request,
                                            UUID callerUserId, boolean callerIsPlatformAdmin) {
        // Existence + tenant-scope guard only — the fetched Employee itself isn't needed below.
        employeeRepository.findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("face_id:manage")) {
                throw new AccessDeniedException("You do not have permission to review Face ID enrollments");
            }
        }

        FaceProfile profile = faceProfileRepository.findByEmployeeIdAndTenantId(employeeId, tenantId)
                .filter(p -> "pending".equals(p.getReviewStatus()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No pending Face ID submission for employee: " + employeeId));

        aiServiceClient.rejectFace(tenantId, employeeId, request.getReason());

        refreshed(profile);
        profile.setReviewedBy(callerUserId);
        profile.setReviewedAt(OffsetDateTime.now());
        FaceProfile saved = faceProfileRepository.save(profile);
        log.info("Face ID enrollment rejected tenantId={} employeeId={} by={} reason={}",
                tenantId, employeeId, callerUserId, request.getReason());

        return toDto(saved);
    }

    /** HR review queue — every profile with a submitted-but-undecided batch, restricted to the
     *  caller's allowed sites the same way every other employee-facing list in this system is
     *  (an employee's "site" is derived from their assignments, since Employee itself carries no
     *  direct site column). */
    @Transactional(readOnly = true)
    public List<FaceIdStatusDto> listPendingReview(UUID tenantId, UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("face_id:manage")) {
                throw new AccessDeniedException("You do not have permission to view Face ID review queue");
            }
        }

        List<FaceProfile> pending = faceProfileRepository.findAllByTenantIdAndReviewStatus(tenantId, "pending");

        Optional<Set<UUID>> allowedSiteIds =
                siteScopeService.resolveAllowedSiteIds(callerUserId, tenantId, callerIsPlatformAdmin);
        if (allowedSiteIds.isPresent()) {
            if (allowedSiteIds.get().isEmpty()) {
                return List.of();
            }
            Set<UUID> visibleEmployeeIds = assignmentRepository
                    .findDistinctEmployeeIdsByTenantIdAndSiteIdIn(tenantId, allowedSiteIds.get());
            pending = pending.stream().filter(p -> visibleEmployeeIds.contains(p.getEmployeeId())).toList();
        }

        List<UUID> employeeIds = pending.stream().map(FaceProfile::getEmployeeId).toList();
        java.util.Map<UUID, Employee> employeesById = employeeIds.isEmpty()
                ? java.util.Map.of()
                : employeeRepository.findAllById(employeeIds).stream()
                        .collect(java.util.stream.Collectors.toMap(Employee::getId, e -> e));

        return pending.stream().map(p -> {
            FaceIdStatusDto dto = toDto(p);
            Employee emp = employeesById.get(p.getEmployeeId());
            if (emp != null) {
                dto.setEmployeeId(emp.getId());
                dto.setEmployeeCode(emp.getEmployeeCode());
                dto.setEmployeeName((emp.getFirstName() + " " + emp.getLastName()).trim());
            }
            return dto;
        }).toList();
    }

    /** The pending submission's reference photo — lets HR actually see who they're
     *  approving/rejecting instead of acting on metadata alone ("duyệt mù", per the Web team's
     *  review). Same permission + site-scope gate as {@link #listPendingReview}; additionally
     *  requires the employee to actually have a pending submission, and — for site-scoped
     *  callers — that the employee falls within their allowed sites. */
    @Transactional(readOnly = true)
    public byte[] getPendingReviewPhoto(UUID tenantId, UUID employeeId,
                                        UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("face_id:manage")) {
                throw new AccessDeniedException("You do not have permission to review Face ID enrollments");
            }
        }

        employeeRepository.findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        Optional<Set<UUID>> allowedSiteIds =
                siteScopeService.resolveAllowedSiteIds(callerUserId, tenantId, callerIsPlatformAdmin);
        if (allowedSiteIds.isPresent()) {
            // Reuse the same "is this employee within one of my allowed sites" check already
            // proven correct elsewhere (EmployeeService/getFaceIdEnrollmentReport) rather than
            // inventing a new query shape here.
            Set<UUID> visibleEmployeeIds = allowedSiteIds.get().isEmpty()
                    ? Set.of()
                    : assignmentRepository.findDistinctEmployeeIdsByTenantIdAndSiteIdIn(tenantId, allowedSiteIds.get());
            if (!visibleEmployeeIds.contains(employeeId)) {
                throw new AccessDeniedException("You do not have permission to view this employee's Face ID review");
            }
        }

        faceProfileRepository.findByEmployeeIdAndTenantId(employeeId, tenantId)
                .filter(p -> "pending".equals(p.getReviewStatus()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No pending Face ID submission for employee: " + employeeId));

        return aiServiceClient.getPendingReviewPhoto(tenantId, employeeId);
    }

    @Transactional
    public FaceIdStatusDto revokeFace(UUID tenantId, UUID employeeId,
                                      UUID callerUserId, boolean callerIsPlatformAdmin) {
        Employee employee = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        requireManageOrSelf(employee, tenantId, callerUserId, callerIsPlatformAdmin);

        FaceProfile profile = faceProfileRepository
                .findByEmployeeIdAndTenantId(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Face ID profile not found for employee: " + employeeId));

        aiServiceClient.revokeFace(tenantId, employeeId);

        log.info("Face ID revoked tenantId={} employeeId={}", tenantId, employeeId);
        return toDto(refreshed(profile));
    }

    /** System-triggered revoke on employee termination — bypasses the normal caller/permission
     *  check since there is no HTTP caller; see EmployeeService.changeEmployeeStatus. No-op if
     *  there is nothing to revoke (never enrolled, or already revoked). */
    @Transactional
    public void autoRevokeOnTermination(UUID tenantId, UUID employeeId) {
        Optional<FaceProfile> profileOpt = faceProfileRepository.findByEmployeeIdAndTenantId(employeeId, tenantId);
        if (profileOpt.isEmpty()) {
            return;
        }
        FaceProfile profile = profileOpt.get();
        boolean hasSomethingToRevoke = !"revoked".equals(profile.getStatus())
                && ("enrolled".equals(profile.getStatus()) || "pending".equals(profile.getReviewStatus())
                        || profile.isConsentGiven());
        if (!hasSomethingToRevoke) {
            return;
        }

        aiServiceClient.revokeFace(tenantId, employeeId);
        log.info("Face ID auto-revoked on termination tenantId={} employeeId={}", tenantId, employeeId);
    }

    @Transactional(readOnly = true)
    public FaceIdStatusDto getStatus(UUID tenantId, UUID employeeId,
                                     UUID callerUserId, boolean callerIsPlatformAdmin) {
        Employee employee = employeeRepository.findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        // The controller's own Javadoc promises "callable by the employee themselves" — this
        // check was missing (caught in review after the App team reported employees getting 403
        // on their own status), so a rank-and-file employee with neither face_id:manage nor
        // employees:read could never load their own Face ID screen.
        boolean isOwnEmployee = employee.getUserId() != null && employee.getUserId().equals(callerUserId);
        if (!callerIsPlatformAdmin && !isOwnEmployee) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("face_id:manage") && !permissions.contains("employees:read")) {
                throw new AccessDeniedException(
                        "You do not have permission to view Face ID status for this employee");
            }
        }

        return faceProfileRepository.findByEmployeeIdAndTenantId(employeeId, tenantId)
                .map(FaceIdService::toDto)
                .orElse(FaceIdStatusDto.builder()
                        .status("not_enrolled")
                        .consentGiven(false)
                        .reviewStatus("none")
                        .build());
    }

    @Transactional
    public VerifyFaceSubmitResponse submitVerify(UUID tenantId, UUID employeeId,
                                                 VerifyFaceRequest request,
                                                 UUID callerUserId, boolean callerIsPlatformAdmin) {
        Employee employee = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        requireManageOrSelf(employee, tenantId, callerUserId, callerIsPlatformAdmin);

        // Existence + status guard only ("must have an active enrolled profile") — no field of
        // the fetched FaceProfile itself is needed below.
        faceProfileRepository.findByEmployeeIdAndTenantId(employeeId, tenantId)
                .filter(p -> "enrolled".equals(p.getStatus()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee has no active Face ID enrollment: " + employeeId));

        FaceVerifyRequest verifyRequest = FaceVerifyRequest.builder()
                .tenantId(tenantId)
                .employeeId(employeeId)
                .status("pending")
                .requiresLiveness(request.isRequiresLiveness())
                .expiresAt(OffsetDateTime.now().plusSeconds(verifyTimeoutSeconds))
                .build();

        faceVerifyRequestRepository.save(verifyRequest);

        faceVerifyJobPublisher.publish(
                tenantId, employeeId, verifyRequest.getId(),
                SOURCE_TYPE_STANDALONE, request.getPhotoBase64(), request.isRequiresLiveness());

        log.info("Face verify job submitted: verifyRequestId={} employeeId={} tenantId={}",
                verifyRequest.getId(), employeeId, tenantId);

        return VerifyFaceSubmitResponse.builder()
                .verifyRequestId(verifyRequest.getId())
                .status("pending")
                .build();
    }

    @Transactional
    public VerifyFaceResultResponse getVerifyResult(UUID tenantId, UUID employeeId,
                                                    UUID verifyRequestId,
                                                    UUID callerUserId, boolean callerIsPlatformAdmin) {
        Employee employee = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        requireManageOrSelf(employee, tenantId, callerUserId, callerIsPlatformAdmin);

        FaceVerifyRequest verifyRequest = faceVerifyRequestRepository
                .findByIdAndTenantIdAndEmployeeId(verifyRequestId, tenantId, employeeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Verify request not found: " + verifyRequestId));

        if ("pending".equals(verifyRequest.getStatus())
                && OffsetDateTime.now().isAfter(verifyRequest.getExpiresAt())) {
            verifyRequest.setStatus("fail");
            verifyRequest.setErrorCode("TIMEOUT");
            faceVerifyRequestRepository.save(verifyRequest);
            log.info("Face verify request timed out: verifyRequestId={}", verifyRequestId);
        }

        return VerifyFaceResultResponse.builder()
                .status(verifyRequest.getStatus())
                .faceVerified(verifyRequest.getFaceVerified())
                .livenessVerified(verifyRequest.getLivenessVerified())
                .score(verifyRequest.getFaceVerifyScore())
                .errorCode(verifyRequest.getErrorCode())
                .build();
    }

    @Transactional
    public void applyVerifyResult(UUID verifyRequestId, UUID tenantId,
                                  Boolean faceVerified, Boolean livenessVerified,
                                  Double faceVerifyScore, String errorCode) {
        faceVerifyRequestRepository.findById(verifyRequestId)
                .filter(r -> r.getTenantId().equals(tenantId))
                .ifPresent(r -> {
                    if (!"pending".equals(r.getStatus())) {
                        log.warn("Face verify callback for non-pending request: id={} status={}",
                                verifyRequestId, r.getStatus());
                        return;
                    }
                    boolean passed = Boolean.TRUE.equals(faceVerified)
                            && (errorCode == null || errorCode.isBlank());
                    r.setStatus(passed ? "pass" : "fail");
                    r.setFaceVerified(faceVerified);
                    r.setLivenessVerified(livenessVerified);
                    r.setFaceVerifyScore(faceVerifyScore);
                    r.setErrorCode(errorCode);
                    faceVerifyRequestRepository.save(r);
                    log.info("Face verify result applied: id={} status={} faceVerified={} score={} error={}",
                            verifyRequestId, r.getStatus(), faceVerified, faceVerifyScore, errorCode);
                });
    }

    public static FaceIdStatusDto toDto(FaceProfile profile) {
        return FaceIdStatusDto.builder()
                .status(profile.getStatus())
                .consentGiven(profile.isConsentGiven())
                .consentGivenAt(profile.getConsentGivenAt())
                .enrolledAt(profile.getEnrolledAt())
                .revokedAt(profile.getRevokedAt())
                .reviewStatus(profile.getReviewStatus())
                .pendingPhotoCount(profile.getPendingPhotoCount())
                .submittedAt(profile.getSubmittedAt())
                .reviewedAt(profile.getReviewedAt())
                .rejectionReason(profile.getRejectionReason())
                .build();
    }
}
