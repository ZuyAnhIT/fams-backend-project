package com.fams.modules.auth.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.auth.dto.request.ChangePasswordRequest;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.shared.exception.InvalidCredentialsException;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ChangePasswordService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final LogoutService logoutService;
    private final UserRoleRepository userRoleRepository;
    private final AuditLogService auditLogService;
    private final int accessTtlMinutes;

    public ChangePasswordService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            BCryptPasswordEncoder passwordEncoder,
            StringRedisTemplate redis,
            LogoutService logoutService,
            UserRoleRepository userRoleRepository,
            AuditLogService auditLogService,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
        this.logoutService = logoutService;
        this.userRoleRepository = userRoleRepository;
        this.auditLogService = auditLogService;
        this.accessTtlMinutes = accessTtlMinutes;
    }

    /**
     * @param rawAccessToken the token used to authenticate THIS request (from the
     *                       Authorization header) — blacklisted directly and immediately so
     *                       it remains deterministic even though the user-wide timestamp
     *                       mechanism also invalidates every sibling session.
     */
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request, String rawAccessToken) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            log.warn("Change password failed: wrong current password for user {}", userId);
            throw new InvalidCredentialsException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // Revoke all refresh tokens so no session can silently get a new access token...
        refreshTokenRepository.revokeAllActiveByUserId(userId, OffsetDateTime.now());

        // ...kill every OTHER already-issued access token (other devices) via the same
        // timestamp mechanism LogoutService.logoutAll() uses...
        String userRevokeKey = LogoutService.USER_REVOKE_PREFIX + userId;
        long revokeMillis = System.currentTimeMillis();
        redis.opsForValue().set(userRevokeKey, String.valueOf(revokeMillis),
                accessTtlMinutes + 1, TimeUnit.MINUTES);

        // ...and blacklist THIS request's own token directly and exactly, so it dies right
        // now regardless of the one-second race above.
        logoutService.blacklistAccessToken(rawAccessToken);

        log.info("Password changed successfully for user {} — all sessions invalidated", userId);

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
                        "CHANGE_PASSWORD",
                        null,
                        null,
                        HttpRequestUtils.currentRequestId(),
                        HttpRequestUtils.currentIpAddress(),
                        HttpRequestUtils.currentUserAgent());
            } catch (Exception ex) {
                log.warn("Failed to record CHANGE_PASSWORD audit for user {}: {}", userId, ex.getMessage());
            }
        }
    }
}
