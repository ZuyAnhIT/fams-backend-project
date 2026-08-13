package com.fams.modules.auth.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.auth.dto.request.ForgotPasswordRequest;
import com.fams.modules.auth.dto.request.ResetPasswordRequest;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PasswordResetService {

    private static final String RESET_TOKEN_PREFIX = "pwd:reset:token:";
    private static final String RESET_RATE_PREFIX  = "pwd:reset:rate:";
    private static final int    TOKEN_TTL_HOURS    = 1;
    private static final int    RATE_WINDOW_MINUTES = 10;

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final UserRoleRepository userRoleRepository;
    private final AuditLogService auditLogService;
    private final String frontendUrl;
    private final int rateLimitMax;

    public PasswordResetService(
            StringRedisTemplate redis,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            BCryptPasswordEncoder passwordEncoder,
            EmailService emailService,
            UserRoleRepository userRoleRepository,
            AuditLogService auditLogService,
            @Value("${app.frontend-url}") String frontendUrl,
            @Value("${app.password-reset.rate-limit-max:3}") int rateLimitMax) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.userRoleRepository = userRoleRepository;
        this.auditLogService = auditLogService;
        this.frontendUrl = frontendUrl;
        this.rateLimitMax = rateLimitMax;
    }

    public void forgotPassword(ForgotPasswordRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        // Rate limit per email to prevent abuse
        String rateKey = RESET_RATE_PREFIX + email;
        Long count = redis.opsForValue().increment(rateKey);
        if (count != null && count == 1) {
            redis.expire(rateKey, RATE_WINDOW_MINUTES, TimeUnit.MINUTES);
        }
        if (count != null && count > rateLimitMax) {
            // Silently return — do not reveal rate limit to caller to avoid timing attacks
            log.warn("Password reset rate limit hit for email={}", email);
            return;
        }

        // Look up user — silently skip if not found (no info leak)
        Optional<User> userOpt = userRepository.findByEmailAndDeletedAtIsNull(email);
        if (userOpt.isEmpty()) {
            log.debug("Password reset requested for unknown email — silently ignored");
            return;
        }

        User user = userOpt.get();
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(RESET_TOKEN_PREFIX + token, user.getId().toString(),
                TOKEN_TTL_HOURS, TimeUnit.HOURS);

        // Points at the FRONTEND's /reset-password screen (web page or native app deep
        // link — see fams-front-app-project docs 07/08), not the backend API path directly:
        // the API endpoint that actually processes the token is POST-only, so a browser/
        // phone opening this link via a plain click could never hit it. The frontend page
        // collects the new password and calls POST /auth/reset-password itself.
        String resetUrl = UriComponentsBuilder.fromUriString(frontendUrl)
                .path("/reset-password")
                .queryParam("token", token)
                .build()
                .encode()
                .toUriString();
        emailService.sendPasswordResetEmail(email, resetUrl);
        log.info("Password reset email sent for user id={}", user.getId());
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String key = RESET_TOKEN_PREFIX + request.getToken();
        String userIdStr = redis.opsForValue().get(key);
        if (userIdStr == null) {
            throw new IllegalArgumentException("Invalid or expired password reset token");
        }

        UUID userId = UUID.fromString(userIdStr);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired password reset token"));

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));

        // Proving control of the account's email is treated as equivalent to waiting out
        // AppConstants.LOCK_DURATION_MINUTES — unlocks immediately rather than making someone
        // who just verified their identity keep waiting on a timer (see AuthService.login,
        // where the lockout email links here specifically for this reason).
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);

        userRepository.save(user);

        // Consume the token immediately to prevent replay
        redis.delete(key);

        // Revoke all existing sessions so the user must log in fresh
        refreshTokenRepository.revokeAllActiveByUserId(userId, OffsetDateTime.now());

        log.info("Password reset successfully for user id={}", userId);

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
                        "RESET_PASSWORD",
                        null,
                        null,
                        HttpRequestUtils.currentRequestId(),
                        HttpRequestUtils.currentIpAddress(),
                        HttpRequestUtils.currentUserAgent());
            } catch (Exception ex) {
                log.warn("Failed to record RESET_PASSWORD audit for user {}: {}", userId, ex.getMessage());
            }
        }
    }
}
