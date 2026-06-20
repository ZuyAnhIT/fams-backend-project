package com.fams.modules.auth.service;

import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.shared.security.JwtProvider;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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
}
