package com.fams.modules.employee.service;

import com.fams.modules.employee.dto.response.FaceIdStatusDto;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.entity.FaceProfile;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.shared.ai.AiServiceClient;
import com.fams.shared.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class FaceIdService {

    private final FaceProfileRepository faceProfileRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRoleRepository userRoleRepository;
    private final AiServiceClient aiServiceClient;
    private final int enrollMinPhotos;
    private final int enrollMaxPhotos;

    public FaceIdService(FaceProfileRepository faceProfileRepository,
                         EmployeeRepository employeeRepository,
                         UserRoleRepository userRoleRepository,
                         AiServiceClient aiServiceClient,
                         @Value("${app.ai.enroll-min-photos:3}") int enrollMinPhotos,
                         @Value("${app.ai.enroll-max-photos:5}") int enrollMaxPhotos) {
        this.faceProfileRepository = faceProfileRepository;
        this.employeeRepository = employeeRepository;
        this.userRoleRepository = userRoleRepository;
        this.aiServiceClient = aiServiceClient;
        this.enrollMinPhotos = enrollMinPhotos;
        this.enrollMaxPhotos = enrollMaxPhotos;
    }

    @Transactional
    public FaceIdStatusDto giveConsent(UUID tenantId, UUID employeeId,
                                       UUID callerUserId, boolean callerIsPlatformAdmin) {
        Employee employee = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        if (!callerIsPlatformAdmin) {
            boolean isOwnEmployee = employee.getUserId() != null
                    && employee.getUserId().equals(callerUserId);
            if (!isOwnEmployee) {
                Set<String> permissions = userRoleRepository
                        .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
                if (!permissions.contains("face_id:manage")) {
                    throw new AccessDeniedException(
                            "You do not have permission to manage Face ID for this employee");
                }
            }
        }

        FaceProfile profile = faceProfileRepository
                .findByEmployeeIdAndTenantId(employeeId, tenantId)
                .orElseGet(() -> FaceProfile.builder()
                        .tenantId(tenantId)
                        .employeeId(employeeId)
                        .consentGiven(false)
                        .status("not_enrolled")
                        .build());

        if (!profile.isConsentGiven()) {
            profile.setConsentGiven(true);
            profile.setConsentGivenAt(OffsetDateTime.now());
        }

        FaceProfile saved = faceProfileRepository.save(profile);
        log.info("Face ID consent recorded tenantId={} employeeId={}", tenantId, employeeId);

        return toDto(saved);
    }

    @Transactional
    public FaceIdStatusDto enrollFace(UUID tenantId, UUID employeeId,
                                      List<MultipartFile> photos,
                                      UUID callerUserId, boolean callerIsPlatformAdmin) {
        Employee employee = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        if (!callerIsPlatformAdmin) {
            boolean isOwnEmployee = employee.getUserId() != null
                    && employee.getUserId().equals(callerUserId);
            if (!isOwnEmployee) {
                Set<String> permissions = userRoleRepository
                        .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
                if (!permissions.contains("face_id:manage")) {
                    throw new AccessDeniedException(
                            "You do not have permission to manage Face ID for this employee");
                }
            }
        }

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

        aiServiceClient.enrollFace(tenantId, employeeId, photos);

        profile.setStatus("enrolled");
        profile.setEnrolledAt(OffsetDateTime.now());
        FaceProfile saved = faceProfileRepository.save(profile);
        log.info("Face ID enrolled tenantId={} employeeId={}", tenantId, employeeId);

        return toDto(saved);
    }

    @Transactional
    public FaceIdStatusDto revokeFace(UUID tenantId, UUID employeeId,
                                      UUID callerUserId, boolean callerIsPlatformAdmin) {
        Employee employee = employeeRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        if (!callerIsPlatformAdmin) {
            boolean isOwnEmployee = employee.getUserId() != null
                    && employee.getUserId().equals(callerUserId);
            if (!isOwnEmployee) {
                Set<String> permissions = userRoleRepository
                        .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
                if (!permissions.contains("face_id:manage")) {
                    throw new AccessDeniedException(
                            "You do not have permission to revoke Face ID for this employee");
                }
            }
        }

        FaceProfile profile = faceProfileRepository
                .findByEmployeeIdAndTenantId(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Face ID profile not found for employee: " + employeeId));

        aiServiceClient.revokeFace(tenantId, employeeId);

        profile.setStatus("revoked");
        profile.setRevokedAt(OffsetDateTime.now());
        FaceProfile saved = faceProfileRepository.save(profile);
        log.info("Face ID revoked tenantId={} employeeId={}", tenantId, employeeId);

        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public FaceIdStatusDto getStatus(UUID tenantId, UUID employeeId,
                                     UUID callerUserId, boolean callerIsPlatformAdmin) {
        employeeRepository.findByIdAndTenantIdAndDeletedAtIsNull(employeeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        if (!callerIsPlatformAdmin) {
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
                        .consentGivenAt(null)
                        .enrolledAt(null)
                        .revokedAt(null)
                        .build());
    }

    public static FaceIdStatusDto toDto(FaceProfile profile) {
        return FaceIdStatusDto.builder()
                .status(profile.getStatus())
                .consentGiven(profile.isConsentGiven())
                .consentGivenAt(profile.getConsentGivenAt())
                .enrolledAt(profile.getEnrolledAt())
                .revokedAt(profile.getRevokedAt())
                .build();
    }
}
