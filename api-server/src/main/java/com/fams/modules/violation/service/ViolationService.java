package com.fams.modules.violation.service;

import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.randomcheck.entity.CheckResponse;
import com.fams.modules.randomcheck.entity.ScheduledCheck;
import com.fams.modules.randomcheck.repository.CheckResponseRepository;
import com.fams.modules.randomcheck.repository.ScheduledCheckRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.violation.dto.request.ConfirmViolationRequest;
import com.fams.modules.violation.dto.request.DismissViolationRequest;
import com.fams.modules.violation.dto.request.UpdateAttendanceImpactRequest;
import com.fams.modules.violation.dto.response.AttendanceImpactResponse;
import com.fams.modules.violation.dto.response.ViolationActionResponse;
import com.fams.modules.violation.dto.response.ViolationDetailResponse;
import com.fams.modules.violation.dto.response.ViolationListResponse;
import com.fams.modules.violation.entity.Violation;
import com.fams.modules.violation.repository.ViolationRepository;
import com.fams.modules.violation.specification.ViolationSpecification;
import com.fams.shared.dto.ExplanationResponse;
import com.fams.shared.dto.SubmitExplanationRequest;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class ViolationService {

    private static final Set<String> SORTABLE_FIELDS =
            Set.of("checkDate", "createdAt", "violationType", "employeeId", "siteId");

    private final ViolationRepository violationRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRoleRepository userRoleRepository;
    private final ScheduledCheckRepository scheduledCheckRepository;
    private final CheckResponseRepository checkResponseRepository;

    public ViolationService(ViolationRepository violationRepository,
                            EmployeeRepository employeeRepository,
                            UserRoleRepository userRoleRepository,
                            ScheduledCheckRepository scheduledCheckRepository,
                            CheckResponseRepository checkResponseRepository) {
        this.violationRepository = violationRepository;
        this.employeeRepository = employeeRepository;
        this.userRoleRepository = userRoleRepository;
        this.scheduledCheckRepository = scheduledCheckRepository;
        this.checkResponseRepository = checkResponseRepository;
    }

    @Transactional
    public ExplanationResponse explainViolation(UUID tenantId, UUID violationId,
                                                 SubmitExplanationRequest request,
                                                 UUID callerUserId) {
        Employee employee = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee profile not found for this tenant"));

        Violation violation = violationRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(violationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Violation not found: " + violationId));

        if (!violation.getEmployeeId().equals(employee.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Violation does not belong to this employee");
        }

        violation.setEmployeeNote(request.getNote());
        violation.setEmployeePhotoUrl(request.getPhotoUrl());
        violationRepository.save(violation);

        log.info("Employee explanation submitted for violation: violationId={} employeeId={}",
                violationId, employee.getId());

        return ExplanationResponse.builder()
                .id(violation.getId())
                .employeeNote(violation.getEmployeeNote())
                .employeePhotoUrl(violation.getEmployeePhotoUrl())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    @Transactional(readOnly = true)
    public PageResponse<ViolationListResponse> listViolations(UUID tenantId,
                                                               UUID employeeId, UUID siteId,
                                                               String violationType, Boolean resolved,
                                                               LocalDate from, LocalDate to,
                                                               String sortBy, String sortDir,
                                                               int page, int size,
                                                               UUID callerUserId,
                                                               boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("violations:list")) {
                throw new AccessDeniedException("You do not have permission to list violations in this tenant");
            }
        }

        String resolvedSort = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "checkDate";
        Sort.Direction dir = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        int cappedSize = Math.min(size, 100);
        PageRequest pageable = PageRequest.of(page, cappedSize, Sort.by(dir, resolvedSort));

        Specification<Violation> spec =
                ViolationSpecification.build(tenantId, employeeId, siteId, violationType, resolved, from, to);

        Page<ViolationListResponse> resultPage = violationRepository.findAll(spec, pageable)
                .map(this::toListResponse);

        log.info("HR violation list: tenantId={} total={}", tenantId, resultPage.getTotalElements());

        return PageResponse.from(resultPage);
    }

    @Transactional(readOnly = true)
    public ViolationDetailResponse getViolationDetail(UUID tenantId, UUID violationId,
                                                       UUID callerUserId,
                                                       boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("violations:read")) {
                throw new AccessDeniedException("You do not have permission to view violations in this tenant");
            }
        }

        Violation violation = violationRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(violationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Violation not found: " + violationId));

        ViolationDetailResponse.ScheduledCheckSummary scheduledCheckSummary = null;
        if (violation.getScheduledCheckId() != null) {
            scheduledCheckSummary = scheduledCheckRepository.findById(violation.getScheduledCheckId())
                    .map(sc -> ViolationDetailResponse.ScheduledCheckSummary.builder()
                            .id(sc.getId())
                            .scheduledAt(sc.getScheduledAt())
                            .expiresAt(sc.getExpiresAt())
                            .status(sc.getStatus())
                            .checkIndex(sc.getCheckIndex())
                            .build())
                    .orElse(null);
        }

        ViolationDetailResponse.CheckResponseEvidence checkResponseEvidence = null;
        if (violation.getCheckResponseId() != null) {
            checkResponseEvidence = checkResponseRepository.findById(violation.getCheckResponseId())
                    .map(cr -> ViolationDetailResponse.CheckResponseEvidence.builder()
                            .id(cr.getId())
                            .respondedAt(cr.getRespondedAt())
                            .latitude(cr.getLatitude())
                            .longitude(cr.getLongitude())
                            .accuracyMeters(cr.getAccuracyMeters())
                            .faceImageUrl(cr.getFaceImageUrl())
                            .livenessScore(cr.getLivenessScore())
                            .locationVerified(cr.isLocationVerified())
                            .faceVerified(cr.getFaceVerified())
                            .livenessVerified(cr.getLivenessVerified())
                            .outcome(cr.getOutcome())
                            .failureReason(cr.getFailureReason())
                            .build())
                    .orElse(null);
        }

        log.info("HR violation detail fetched: tenantId={} violationId={}", tenantId, violationId);

        return ViolationDetailResponse.builder()
                .id(violation.getId())
                .tenantId(violation.getTenantId())
                .employeeId(violation.getEmployeeId())
                .siteId(violation.getSiteId())
                .scheduledCheckId(violation.getScheduledCheckId())
                .checkResponseId(violation.getCheckResponseId())
                .checkinId(violation.getCheckinId())
                .violationType(violation.getViolationType())
                .checkDate(violation.getCheckDate())
                .description(violation.getDescription())
                .resolved(violation.isResolved())
                .resolvedAt(violation.getResolvedAt())
                .resolvedBy(violation.getResolvedBy())
                .employeeNote(violation.getEmployeeNote())
                .employeePhotoUrl(violation.getEmployeePhotoUrl())
                .createdAt(violation.getCreatedAt())
                .scheduledCheck(scheduledCheckSummary)
                .checkResponse(checkResponseEvidence)
                .build();
    }

    @Transactional
    public ViolationActionResponse confirmViolation(UUID tenantId, UUID violationId,
                                                     ConfirmViolationRequest request,
                                                     UUID callerUserId,
                                                     boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("violations:update")) {
                throw new AccessDeniedException("You do not have permission to update violations in this tenant");
            }
        }

        Violation violation = violationRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(violationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Violation not found: " + violationId));

        if (violation.isResolved()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Violation has already been resolved with outcome: " + violation.getResolution());
        }

        OffsetDateTime now = OffsetDateTime.now();
        violation.setResolved(true);
        violation.setResolution("confirmed");
        violation.setResolutionReason(request != null ? request.getNote() : null);
        violation.setResolvedAt(now);
        violation.setResolvedBy(callerUserId);
        violationRepository.save(violation);

        log.info("Violation confirmed: tenantId={} violationId={} by userId={}", tenantId, violationId, callerUserId);

        return ViolationActionResponse.builder()
                .id(violation.getId())
                .resolution("confirmed")
                .resolutionReason(violation.getResolutionReason())
                .resolved(true)
                .resolvedAt(now)
                .resolvedBy(callerUserId)
                .build();
    }

    @Transactional
    public ViolationActionResponse dismissViolation(UUID tenantId, UUID violationId,
                                                     DismissViolationRequest request,
                                                     UUID callerUserId,
                                                     boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("violations:update")) {
                throw new AccessDeniedException("You do not have permission to update violations in this tenant");
            }
        }

        Violation violation = violationRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(violationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Violation not found: " + violationId));

        if (violation.isResolved()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Violation has already been resolved with outcome: " + violation.getResolution());
        }

        OffsetDateTime now = OffsetDateTime.now();
        violation.setResolved(true);
        violation.setResolution("dismissed");
        violation.setResolutionReason(request.getReason());
        violation.setResolvedAt(now);
        violation.setResolvedBy(callerUserId);
        violationRepository.save(violation);

        log.info("Violation dismissed: tenantId={} violationId={} by userId={}", tenantId, violationId, callerUserId);

        return ViolationActionResponse.builder()
                .id(violation.getId())
                .resolution("dismissed")
                .resolutionReason(violation.getResolutionReason())
                .resolved(true)
                .resolvedAt(now)
                .resolvedBy(callerUserId)
                .build();
    }

    @Transactional
    public AttendanceImpactResponse updateAttendanceImpact(UUID tenantId, UUID violationId,
                                                            UpdateAttendanceImpactRequest request,
                                                            UUID callerUserId,
                                                            boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("violations:update")) {
                throw new AccessDeniedException("You do not have permission to update violations in this tenant");
            }
        }

        Violation violation = violationRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(violationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Violation not found: " + violationId));

        violation.setAffectsAttendance(request.getAffectsAttendance());
        violationRepository.save(violation);

        log.info("Attendance impact updated: tenantId={} violationId={} affectsAttendance={} by userId={}",
                tenantId, violationId, request.getAffectsAttendance(), callerUserId);

        return AttendanceImpactResponse.builder()
                .id(violation.getId())
                .affectsAttendance(violation.isAffectsAttendance())
                .build();
    }

    private ViolationListResponse toListResponse(Violation v) {
        return ViolationListResponse.builder()
                .id(v.getId())
                .employeeId(v.getEmployeeId())
                .siteId(v.getSiteId())
                .violationType(v.getViolationType())
                .checkDate(v.getCheckDate())
                .description(v.getDescription())
                .resolved(v.isResolved())
                .resolvedAt(v.getResolvedAt())
                .employeeNote(v.getEmployeeNote())
                .employeePhotoUrl(v.getEmployeePhotoUrl())
                .createdAt(v.getCreatedAt())
                .build();
    }
}
