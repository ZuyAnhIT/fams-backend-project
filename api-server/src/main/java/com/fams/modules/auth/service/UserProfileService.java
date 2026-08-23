package com.fams.modules.auth.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.auth.dto.request.UpdateProfileRequest;
import com.fams.modules.auth.dto.response.UserProfileResponse;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.auth.specification.UserSpecification;
import com.fams.modules.auth.util.PhoneNumbers;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.util.PrimaryRoleResolver;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.InvalidCredentialsException;
import com.fams.shared.pagination.PageResponse;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class UserProfileService {

    private static final String PHONE_CHANGE_OTP_PURPOSE = "PROFILE_PHONE_CHANGE";

    private final UserRepository userRepository;
    private final AvatarStorageService avatarStorageService;
    private final EmailVerificationService emailVerificationService;
    private final EmailService emailService;
    private final PhoneOtpService phoneOtpService;
    private final FirebasePhoneTokenVerifier firebasePhoneTokenVerifier;
    private final UserRoleRepository userRoleRepository;
    private final TenantRepository tenantRepository;
    private final AuditLogService auditLogService;
    private final String frontendUrl;

    public UserProfileService(UserRepository userRepository,
                              AvatarStorageService avatarStorageService,
                              EmailVerificationService emailVerificationService,
                              EmailService emailService,
                              PhoneOtpService phoneOtpService,
                              FirebasePhoneTokenVerifier firebasePhoneTokenVerifier,
                              UserRoleRepository userRoleRepository,
                              TenantRepository tenantRepository,
                              AuditLogService auditLogService,
                              @Value("${app.frontend-url}") String frontendUrl) {
        this.userRepository = userRepository;
        this.avatarStorageService = avatarStorageService;
        this.emailVerificationService = emailVerificationService;
        this.emailService = emailService;
        this.phoneOtpService = phoneOtpService;
        this.firebasePhoneTokenVerifier = firebasePhoneTokenVerifier;
        this.userRoleRepository = userRoleRepository;
        this.tenantRepository = tenantRepository;
        this.auditLogService = auditLogService;
        this.frontendUrl = frontendUrl;
    }

    /**
     * Issue #4 (docs/issues/ISSUES.md): actual file upload for the avatar. This is the ONLY
     * way a user can set their own avatar — {@link #updateProfile} does not accept an
     * arbitrary URL string, specifically so a client can't just paste any online image link
     * and claim it as their avatar. Every avatar a user sets themselves is guaranteed to
     * have actually gone through {@link AvatarStorageService} (S3/MinIO), so its URL is
     * always one FAMS controls and can delete later.
     *
     * The one legitimate exception is Google login (see GoogleLoginService): that copies
     * the picture URL Google itself returns for the account, which is a trusted external
     * source the user proved ownership of by signing in — a different situation from a
     * client-supplied arbitrary string.
     */
    @Transactional
    public UserProfileResponse updateAvatarFile(UUID userId, MultipartFile file) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        String previousUrl = user.getAvatarUrl();
        String newUrl = avatarStorageService.store(userId, file);
        user.setAvatarUrl(newUrl);
        userRepository.save(user);

        avatarStorageService.deleteIfManaged(previousUrl);

        log.info("Avatar file uploaded for user {}", userId);
        return toResponse(user);
    }

    /**
     * Symmetric counterpart to {@link #updateAvatarFile} — needed now that
     * {@link #updateProfile} can no longer clear the avatar by accepting an empty
     * `avatarUrl` string (that capability was removed together with the arbitrary-URL
     * escape hatch it was part of).
     */
    @Transactional
    public UserProfileResponse deleteAvatar(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        String previousUrl = user.getAvatarUrl();
        user.setAvatarUrl(null);
        userRepository.save(user);

        avatarStorageService.deleteIfManaged(previousUrl);

        log.info("Avatar removed for user {}", userId);
        return toResponse(user);
    }

    private static final java.util.Set<String> USER_SORTABLE_FIELDS =
            java.util.Set.of("email", "displayName", "createdAt", "lastLoginAt");

    @Transactional(readOnly = true)
    public PageResponse<UserProfileResponse> searchUsers(String search, Boolean isActive, Boolean isPlatformAdmin,
                                                         String sortBy, String sortDir, int page, int size) {
        int clampedSize = Math.min(size, 100);
        String resolvedSortBy = USER_SORTABLE_FIELDS.contains(sortBy) ? sortBy : "email";
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        PageRequest pageable = PageRequest.of(page, clampedSize, Sort.by(direction, resolvedSortBy));
        return PageResponse.from(
                userRepository.findAll(UserSpecification.build(search, isActive, isPlatformAdmin), pageable)
                        .map(this::toResponse));
    }

    /**
     * Email and phone are deliberately NOT handled here — filling in the identifier missing
     * at registration, or changing the existing one, must go through
     * {@link #requestEmailChange}/{@link EmailVerificationService#confirmEmailChange} and
     * {@link #requestPhoneChange}/{@link #confirmPhoneChange}. Both require proof of
     * ownership (email link / SMS OTP) BEFORE the change is written to {@code users}, so an
     * unverified pending change can never interfere with the account's existing, already-
     * verified login channel.
     */
    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        Map<String, Object> before = profileAuditSnapshot(user);

        if (StringUtils.hasText(request.getDisplayName())) {
            user.setDisplayName(request.getDisplayName());
        }

        // Issue #4 (docs/issues/ISSUES.md): hometown/gender/address follow the same
        // "blank means not provided" convention as displayName above; dateOfBirth has no
        // blank-string equivalent so any non-null value (validated @Past) is applied.
        if (StringUtils.hasText(request.getHometown())) {
            user.setHometown(request.getHometown());
        }
        if (StringUtils.hasText(request.getGender())) {
            user.setGender(request.getGender());
        }
        if (StringUtils.hasText(request.getAddress())) {
            user.setAddress(request.getAddress());
        }
        if (request.getDateOfBirth() != null) {
            user.setDateOfBirth(request.getDateOfBirth());
        }

        userRepository.save(user);
        log.info("Profile updated for user {}", userId);

        // Only logged when the account belongs to a tenant — audit viewing is tenant-scoped
        // (Tenant Admin/HR), so a tenant-less account's entry would have no realistic viewer.
        List<UserRole> roles = userRoleRepository.findAllActiveByUserId(userId);
        if (!roles.isEmpty()) {
            try {
                auditLogService.record(
                        roles.get(0).getTenantId(),
                        userId,
                        user.getEmail() != null ? user.getEmail() : user.getPhone(),
                        "USER",
                        userId.toString(),
                        "UPDATE_PROFILE",
                        before,
                        profileAuditSnapshot(user),
                        HttpRequestUtils.currentRequestId(),
                        HttpRequestUtils.currentIpAddress(),
                        HttpRequestUtils.currentUserAgent());
            } catch (Exception ex) {
                log.warn("Failed to record UPDATE_PROFILE audit for user {}: {}", userId, ex.getMessage());
            }
        }

        return toResponse(user);
    }

    private Map<String, Object> profileAuditSnapshot(User user) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("displayName", user.getDisplayName());
        snapshot.put("hometown", user.getHometown());
        snapshot.put("gender", user.getGender());
        snapshot.put("address", user.getAddress());
        snapshot.put("dateOfBirth", user.getDateOfBirth());
        return snapshot;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Add/change email — verify-before-write (step 1: request, step 2: EmailVerificationService.confirmEmailChange)
    // ════════════════════════════════════════════════════════════════════════

    @Transactional
    public void requestEmailChange(UUID userId, String newEmail) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        String normalized = newEmail.trim().toLowerCase();
        if (normalized.equals(user.getEmail())) {
            return; // already this email — no-op, whether or not it's verified yet
        }
        userRepository.findByEmailAndDeletedAtIsNull(normalized).ifPresent(existing -> {
            if (!existing.getId().equals(userId)) {
                throw new DuplicateResourceException("Email address is already in use");
            }
        });

        String token = emailVerificationService.generateChangeToken(userId, normalized);
        String verificationUrl = UriComponentsBuilder.fromUriString(frontendUrl)
                .path("/verify-email")
                .queryParam("token", token)
                .queryParam("mode", "email-change")
                .build()
                .encode()
                .toUriString();
        emailService.sendVerificationEmail(normalized, verificationUrl);
        log.info("Email change requested for user {}", userId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Add/change phone — verify-before-write via SMS OTP (mirrors registration's OTP flow)
    // ════════════════════════════════════════════════════════════════════════

    @Transactional
    public void requestPhoneChange(UUID userId, String newPhone) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        String normalized = PhoneNumbers.normalize(newPhone);
        if (normalized.equals(user.getPhone())) {
            return; // already this number — no-op
        }
        userRepository.findByPhoneAndDeletedAtIsNull(normalized).ifPresent(existing -> {
            if (!existing.getId().equals(userId)) {
                throw new DuplicateResourceException("Phone number is already in use");
            }
        });

        phoneOtpService.sendOtp(normalized, PHONE_CHANGE_OTP_PURPOSE);
        log.info("Phone change OTP requested for user {}", userId);
    }

    @Transactional
    public UserProfileResponse confirmPhoneChange(UUID userId, String newPhone, String otpCode) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        String normalized = PhoneNumbers.normalize(newPhone);
        phoneOtpService.verifyOtp(normalized, PHONE_CHANGE_OTP_PURPOSE, otpCode);

        // Re-check uniqueness at commit time too — the OTP's 5-minute TTL is short, but not
        // zero, so another user could still have claimed this number in between.
        userRepository.findByPhoneAndDeletedAtIsNull(normalized).ifPresent(existing -> {
            if (!existing.getId().equals(userId)) {
                throw new DuplicateResourceException("Phone number is already in use");
            }
        });

        user.setPhone(normalized);
        user.setPhoneVerified(true);
        userRepository.save(user);
        log.info("Phone changed and verified for user {}", userId);

        return toResponse(user);
    }

    @Transactional
    public UserProfileResponse confirmPhoneChangeWithFirebase(UUID userId, String firebaseIdToken) {
        String phone = firebasePhoneTokenVerifier.verifyAndExtractPhone(firebaseIdToken);
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        String normalized = PhoneNumbers.normalize(phone);
        if (!normalized.equals(user.getPhone())) {
            userRepository.findByPhoneAndDeletedAtIsNull(normalized).ifPresent(existing -> {
                if (!existing.getId().equals(userId)) {
                    throw new DuplicateResourceException("Phone number is already in use");
                }
            });
            user.setPhone(normalized);
        }
        user.setPhoneVerified(true);
        userRepository.save(user);
        log.info("Phone changed and verified via Firebase for user {}", userId);

        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));
        return toResponse(user);
    }

    private UserProfileResponse toResponse(User user) {
        // #10 (2026-08-19): totpEnabled + current tenant were missing — AC gap. Primary tenant
        // resolved via the exact same PrimaryRoleResolver used by AuthService.login to pick the
        // JWT's tenant claim, so "current tenant" here matches what a fresh login would land the
        // user on (no separate per-session tenant concept exists yet — see JwtAuthFilter/
        // FamsUserDetails, neither carries a tenantId today).
        List<UserRole> roles = userRoleRepository.findAllActiveByUserId(user.getId());
        UserRole primary = PrimaryRoleResolver.pickPrimary(roles);
        UUID currentTenantId = primary != null ? primary.getTenantId() : null;
        String currentTenantName = currentTenantId != null
                ? tenantRepository.findByIdAndDeletedAtIsNull(currentTenantId).map(t -> t.getName()).orElse(null)
                : null;
        String currentRoleName = primary != null ? primary.getRole().getName() : null;

        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .emailVerified(user.isEmailVerified())
                .phone(user.getPhone())
                .phoneVerified(user.isPhoneVerified())
                .displayName(user.getDisplayName())
                .avatarUrl(user.getAvatarUrl())
                .dateOfBirth(user.getDateOfBirth())
                .hometown(user.getHometown())
                .gender(user.getGender())
                .address(user.getAddress())
                .googleLinked(user.getGoogleId() != null)
                .isActive(user.isActive())
                .isPlatformAdmin(user.isPlatformAdmin())
                .totpEnabled(user.isTotpEnabled())
                .currentTenantId(currentTenantId)
                .currentTenantName(currentTenantName)
                .currentTenantRole(currentRoleName)
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
