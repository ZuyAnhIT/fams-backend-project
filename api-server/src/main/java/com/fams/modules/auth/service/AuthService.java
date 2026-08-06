package com.fams.modules.auth.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.auth.dto.request.LoginRequest;
import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.entity.RefreshToken;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.auth.util.PhoneNumbers;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.constants.AppConstants;
import com.fams.shared.exception.AccountLockedException;
import com.fams.shared.exception.EmailNotVerifiedException;
import com.fams.shared.exception.InvalidCredentialsException;
import com.fams.shared.exception.TenantSuspendedException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.security.JwtProvider;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AuthService {

    static final String TOTP_PENDING_PREFIX  = "login:totp:pending:";
    static final int    TOTP_PENDING_TTL_MIN = 5;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRoleRepository userRoleRepository;
    private final TenantRepository tenantRepository;
    private final JwtProvider jwtProvider;
    private final BCryptPasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final AuditLogService auditLogService;
    private final EmailService emailService;
    private final String frontendUrl;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            UserRoleRepository userRoleRepository,
            TenantRepository tenantRepository,
            JwtProvider jwtProvider,
            BCryptPasswordEncoder passwordEncoder,
            StringRedisTemplate redis,
            AuditLogService auditLogService,
            EmailService emailService,
            @Value("${app.frontend-url}") String frontendUrl,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes,
            @Value("${app.jwt.refresh-ttl-days}") int refreshTtlDays) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRoleRepository = userRoleRepository;
        this.tenantRepository = tenantRepository;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
        this.auditLogService = auditLogService;
        this.emailService = emailService;
        this.frontendUrl = frontendUrl;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
    }

    /**
     * Đăng nhập bằng email + password hoặc số điện thoại + password.
     *
     * Thứ tự kiểm tra:
     *  1. Tìm user theo identifier (email hoặc phone)
     *  2. Kiểm tra account locked
     *  3. Kiểm tra password
     *  4. Kiểm tra account active
     *  5. Kiểm tra email verified (chỉ áp dụng nếu login bằng email)
     *  6. Reset failed attempts
     *  7. Resolve primary tenant
     *  8. Check tenant suspended
     *  9. TOTP gate (nếu bật)
     * 10. Issue token pair + audit
     */
    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class})
    public LoginResponse login(LoginRequest request) {

        // ── 1. Tìm user theo identifier (email hoặc phone) ──────────────────────
        User user = resolveUser(request.getIdentifier());

        // ── 2. Kiểm tra account bị khóa tạm thời ───────────────────────────────
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(OffsetDateTime.now())) {
            throw new AccountLockedException(user.getLockedUntil());
        }

        // ── 3. Xác minh password ────────────────────────────────────────────────
        if (user.getPasswordHash() == null ||
                !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);

            if (attempts >= AppConstants.MAX_FAILED_ATTEMPTS) {
                OffsetDateTime lockUntil = OffsetDateTime.now()
                        .plusMinutes(AppConstants.LOCK_DURATION_MINUTES);
                user.setLockedUntil(lockUntil);
                userRepository.save(user);
                log.warn("Account locked for user {} until {}", user.getId(), lockUntil);

                // Give the real owner an immediate way back in instead of only a timer —
                // PasswordResetService.resetPassword clears lockedUntil as soon as the reset
                // succeeds, so this link is a genuine unlock path, not just a notice.
                if (user.getEmail() != null) {
                    String forgotPasswordUrl = frontendUrl + "/forgot-password";
                    emailService.sendAccountLockedEmail(user.getEmail(), lockUntil.toString(), forgotPasswordUrl);
                }

                throw new AccountLockedException(lockUntil);
            }

            userRepository.save(user);
            throw new InvalidCredentialsException("Invalid credentials");
        }

        // ── 4. Kiểm tra tài khoản active ────────────────────────────────────────
        if (!user.isActive()) {
            throw new InvalidCredentialsException("Account is not active");
        }

        // ── 5. Kiểm tra email verified — CHỈ áp dụng nếu account có email ───────
        // Lưu ý: user đăng ký thuần bằng phone có email=null → bỏ qua bước này.
        // User có cả email lẫn phone (email chưa verify) vẫn bị chặn dù đang login
        // bằng phone, vì email_verified là trạng thái của TÀI KHOẢN, không phải của
        // kênh đăng nhập đang dùng.
        if (user.getEmail() != null && !user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }

        // ── 6. Reset failed attempts sau khi xác thực thành công ────────────────
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        // ── 7. Resolve primary tenant & role cho JWT claims ─────────────────────
        List<UserRole> roles = userRoleRepository.findAllActiveByUserId(user.getId());
        UUID primaryTenantId = roles.isEmpty() ? null : roles.get(0).getTenantId();
        String primaryRole   = roles.isEmpty() ? null : roles.get(0).getRole().getName();

        // ── 8. Block nếu primary tenant bị suspend ──────────────────────────────
        if (!user.isPlatformAdmin() && primaryTenantId != null) {
            tenantRepository.findByIdAndDeletedAtIsNull(primaryTenantId).ifPresent(tenant -> {
                if ("suspended".equals(tenant.getStatus())) {
                    throw new TenantSuspendedException();
                }
            });
        }

        // ── 9. TOTP gate ─────────────────────────────────────────────────────────
        String deviceId = (request.getDeviceId() != null) ? request.getDeviceId() : "unknown";
        if (user.isTotpEnabled()) {
            String pendingToken = UUID.randomUUID().toString();
            String tenantStr = primaryTenantId != null ? primaryTenantId.toString() : "";
            String roleStr   = primaryRole   != null ? primaryRole               : "";
            String value = user.getId() + "|" + user.getEmail() + "|" + deviceId
                    + "|" + user.isPlatformAdmin() + "|" + tenantStr + "|" + roleStr;
            redis.opsForValue().set(
                    TOTP_PENDING_PREFIX + pendingToken, value,
                    TOTP_PENDING_TTL_MIN, TimeUnit.MINUTES);
            log.info("TOTP required for user {} — pending token issued", user.getId());
            return LoginResponse.builder()
                    .totpRequired(true)
                    .pendingToken(pendingToken)
                    .build();
        }

        // ── 10. Issue token pair ─────────────────────────────────────────────────
        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), deviceId,
                user.isPlatformAdmin(), primaryTenantId, primaryRole);

        String rawRefreshToken = jwtProvider.generateRefreshTokenRaw();
        String tokenHash       = jwtProvider.hashToken(rawRefreshToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .deviceId(deviceId)
                .userAgent(HttpRequestUtils.currentUserAgent())
                .ipAddress(HttpRequestUtils.currentIpAddress())
                .expiresAt(OffsetDateTime.now().plusDays(refreshTtlDays))
                .activeTenantId(primaryTenantId)
                .build();
        refreshTokenRepository.save(refreshToken);

        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);

        try {
            auditLogService.record(
                    primaryTenantId,
                    user.getId(),
                    user.getEmail() != null ? user.getEmail() : user.getPhone(),
                    "USER",
                    user.getId().toString(),
                    "LOGIN",
                    null,
                    null,
                    HttpRequestUtils.currentRequestId(),
                    HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception ex) {
            // Audit không được làm hỏng flow login chính
            log.warn("Failed to record LOGIN audit for user {}: {}", user.getId(), ex.getMessage());
        }

        return LoginResponse.builder()
                .userId(user.getId())
                .activeTenantId(primaryTenantId)
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .tokenType("Bearer")
                .expiresIn((long) accessTtlMinutes * 60)
                .build();
    }

    /**
     * Tìm user theo identifier — email hoặc số điện thoại.
     *
     * Heuristic phân biệt: chứa '@' → coi là email; ngược lại → coi là phone
     * và chuẩn hoá về E.164 (giống RegisterService.normalizePhone) trước khi
     * tra DB, vì phone được lưu dạng +84xxx.
     *
     * Luôn ném InvalidCredentialsException (không phải 404) để tránh lộ
     * thông tin "tài khoản có tồn tại hay không" (user enumeration).
     */
    private User resolveUser(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            throw new InvalidCredentialsException("Invalid credentials");
        }

        String trimmed = identifier.trim();

        if (trimmed.contains("@")) {
            return userRepository.findByEmailAndDeletedAtIsNull(trimmed.toLowerCase())
                    .orElseThrow(() -> new InvalidCredentialsException("Invalid credentials"));
        }

        String normalizedPhone = PhoneNumbers.normalize(trimmed);
        return userRepository.findByPhoneAndDeletedAtIsNull(normalizedPhone)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid credentials"));
    }

    /**
     * Switch active tenant — giữ nguyên logic cũ, không thay đổi.
     */
    @Transactional
    public LoginResponse switchTenant(UUID callerUserId, UUID targetTenantId, String rawRefreshToken) {
        User user = userRepository.findByIdAndDeletedAtIsNull(callerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String hash = jwtProvider.hashToken(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
        if (!stored.getUser().getId().equals(callerUserId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Refresh token does not belong to this user");
        }
        if (stored.getRevokedAt() != null || OffsetDateTime.now().isAfter(stored.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token is no longer valid");
        }

        List<UserRole> targetRoles = userRoleRepository
                .findActiveByUserIdAndTenantId(callerUserId, targetTenantId);
        if (targetRoles.isEmpty()) {
            throw new AccessDeniedException("You do not have a role in this company");
        }
        String targetRole = targetRoles.get(0).getRole().getName();

        tenantRepository.findByIdAndDeletedAtIsNull(targetTenantId).ifPresentOrElse(tenant -> {
            if ("suspended".equals(tenant.getStatus())) throw new TenantSuspendedException();
        }, () -> {
            throw new ResourceNotFoundException("Tenant not found: " + targetTenantId);
        });

        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), stored.getDeviceId(),
                user.isPlatformAdmin(), targetTenantId, targetRole);

        stored.setRevokedAt(OffsetDateTime.now());
        refreshTokenRepository.save(stored);

        String rawNew = jwtProvider.generateRefreshTokenRaw();
        RefreshToken newToken = RefreshToken.builder()
                .user(user)
                .tokenHash(jwtProvider.hashToken(rawNew))
                .deviceId(stored.getDeviceId())
                .userAgent(HttpRequestUtils.currentUserAgent())
                .ipAddress(HttpRequestUtils.currentIpAddress())
                .expiresAt(OffsetDateTime.now().plusDays(refreshTtlDays))
                .activeTenantId(targetTenantId)
                .build();
        refreshTokenRepository.save(newToken);

        log.info("User {} switched active tenant to {}", callerUserId, targetTenantId);

        return LoginResponse.builder()
                .userId(user.getId())
                .activeTenantId(targetTenantId)
                .accessToken(accessToken)
                .refreshToken(rawNew)
                .tokenType("Bearer")
                .expiresIn((long) accessTtlMinutes * 60)
                .build();
    }
}