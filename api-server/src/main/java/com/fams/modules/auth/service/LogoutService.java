package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.response.SessionResponse;
import com.fams.modules.auth.entity.RefreshToken;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.security.JwtProvider;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LogoutService {

    public static final String BLACKLIST_PREFIX = "jwt:blacklist:";
    public static final String USER_REVOKE_PREFIX = "jwt:user_revoke:";

    private final StringRedisTemplate redis;
    private final JwtProvider jwtProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final int accessTtlMinutes;

    public LogoutService(StringRedisTemplate redis,
                         JwtProvider jwtProvider,
                         RefreshTokenRepository refreshTokenRepository,
                         @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes) {
        this.redis = redis;
        this.jwtProvider = jwtProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.accessTtlMinutes = accessTtlMinutes;
    }

    @Transactional
    public void logout(String rawAccessToken, String rawRefreshToken) {
        blacklistAccessToken(rawAccessToken);
        revokeRefreshToken(rawRefreshToken);
    }

    private void blacklistAccessToken(String rawToken) {
        try {
            Claims claims = jwtProvider.parseAccessToken(rawToken);
            long expiryMillis = claims.getExpiration().getTime();
            long remainingSeconds = (expiryMillis - System.currentTimeMillis()) / 1000;
            if (remainingSeconds > 0) {
                String key = BLACKLIST_PREFIX + jwtProvider.hashToken(rawToken);
                redis.opsForValue().set(key, "1", remainingSeconds, TimeUnit.SECONDS);
                log.debug("Access token blacklisted for {} seconds", remainingSeconds);
            }
        } catch (Exception e) {
            log.debug("Could not blacklist access token: {}", e.getMessage());
        }
    }

    @Transactional
    public void logoutAll(String rawAccessToken, UUID userId) {
        blacklistAccessToken(rawAccessToken);

        String userRevokeKey = USER_REVOKE_PREFIX + userId;
        long revokeSeconds = System.currentTimeMillis() / 1000;
        redis.opsForValue().set(userRevokeKey, String.valueOf(revokeSeconds),
                accessTtlMinutes + 1, TimeUnit.MINUTES);

        refreshTokenRepository.revokeAllActiveByUserId(userId, OffsetDateTime.now());
        log.debug("Logout-all executed for user {}", userId);
    }

    private void revokeRefreshToken(String rawRefreshToken) {
        String hash = jwtProvider.hashToken(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(rt -> {
            rt.setRevokedAt(OffsetDateTime.now());
            refreshTokenRepository.save(rt);
            log.debug("Refresh token revoked for device: {}", rt.getDeviceId());
        });
    }

    /**
     * Issue #6 (docs/issues/ISSUES.md): lists the caller's active sessions/devices — this
     * endpoint didn't exist before, so there was no way for a user to see what's logged in
     * at all. `currentDeviceId` (from the request's own JWT) marks which entry is "this
     * one", the same way most systems' "manage devices" screens do.
     */
    @Transactional(readOnly = true)
    public List<SessionResponse> listSessions(UUID userId, String currentDeviceId) {
        return refreshTokenRepository.findAllActiveByUserId(userId, OffsetDateTime.now()).stream()
                .map(rt -> SessionResponse.builder()
                        .id(rt.getId())
                        .deviceId(rt.getDeviceId())
                        .userAgent(rt.getUserAgent())
                        .ipAddress(rt.getIpAddress())
                        .createdAt(rt.getCreatedAt())
                        .lastUsedAt(rt.getLastUsedAt())
                        .expiresAt(rt.getExpiresAt())
                        .current(Objects.equals(rt.getDeviceId(), currentDeviceId))
                        .build())
                .toList();
    }

    /**
     * Issue #6 (docs/issues/ISSUES.md): revokes ONE specific session by its ID (as shown in
     * {@link #listSessions}) — previously the only way to "log out" was to already hold
     * that exact device's raw refresh token, which makes "log out my old phone from my
     * laptop" impossible. Ownership is checked (`findByIdAndUserIdAndRevokedAtIsNull`) so a
     * user can only revoke their own sessions.
     */
    @Transactional
    public void logoutSession(UUID userId, UUID sessionId) {
        RefreshToken token = refreshTokenRepository.findByIdAndUserIdAndRevokedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found"));
        token.setRevokedAt(OffsetDateTime.now());
        refreshTokenRepository.save(token);
        log.info("Session {} revoked by user {}", sessionId, userId);
    }

    /**
     * Issue #6 (docs/issues/ISSUES.md): "log out all OTHER devices" — {@link #logoutAll}
     * always killed the caller's own current session too, with no option to keep it. Most
     * real session-management screens offer both; this is the "keep me logged in here" one.
     */
    @Transactional
    public void logoutAllExceptCurrent(UUID userId, String currentDeviceId) {
        refreshTokenRepository.revokeAllActiveByUserIdExceptDevice(
                userId, OffsetDateTime.now(), currentDeviceId);
        log.info("Logout-all-except-current executed for user {} (kept device {})", userId, currentDeviceId);
    }
}
