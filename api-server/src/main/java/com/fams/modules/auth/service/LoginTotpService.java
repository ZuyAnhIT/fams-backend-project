package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.request.LoginTotpRequest;
import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.entity.RefreshToken;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.shared.exception.InvalidCredentialsException;
import com.fams.shared.security.JwtProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
public class LoginTotpService {

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final TotpService totpService;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public LoginTotpService(
            StringRedisTemplate redis,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtProvider jwtProvider,
            TotpService totpService,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes,
            @Value("${app.jwt.refresh-ttl-days}") int refreshTtlDays) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProvider = jwtProvider;
        this.totpService = totpService;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
    }

    @Transactional
    public LoginResponse loginWithTotp(LoginTotpRequest request) {
        String key = AuthService.TOTP_PENDING_PREFIX + request.getPendingToken();
        String value = redis.opsForValue().get(key);
        if (value == null) {
            throw new InvalidCredentialsException("Invalid or expired TOTP session");
        }

        // value format: userId|email|deviceId|isPlatformAdmin|tenantId|role
        String[] parts = value.split("\\|", 6);
        UUID userId = UUID.fromString(parts[0]);
        String email = parts[1];
        String deviceId = parts[2];
        boolean isPlatformAdmin = Boolean.parseBoolean(parts[3]);
        UUID tenantId = (parts.length > 4 && !parts[4].isEmpty()) ? UUID.fromString(parts[4]) : null;
        String role = (parts.length > 5 && !parts[5].isEmpty()) ? parts[5] : null;

        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid or expired TOTP session"));

        if (!user.isTotpEnabled() || user.getTotpSecret() == null) {
            throw new InvalidCredentialsException("TOTP is not configured for this account");
        }

        if (!totpService.verifyCode(user.getTotpSecret(), request.getCode())) {
            log.warn("Invalid TOTP code for user {}", email);
            throw new InvalidCredentialsException("Invalid TOTP code");
        }

        // Consume the pending token immediately to prevent replay
        redis.delete(key);

        String accessToken = jwtProvider.generateAccessToken(userId, email, deviceId, isPlatformAdmin, tenantId, role);

        String rawRefresh = jwtProvider.generateRefreshTokenRaw();
        String tokenHash = jwtProvider.hashToken(rawRefresh);
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .deviceId(deviceId)
                .expiresAt(OffsetDateTime.now().plusDays(refreshTtlDays))
                .build();
        refreshTokenRepository.save(refreshToken);

        log.info("TOTP login successful for user {}", email);
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefresh)
                .tokenType("Bearer")
                .expiresIn((long) accessTtlMinutes * 60)
                .build();
    }
}
