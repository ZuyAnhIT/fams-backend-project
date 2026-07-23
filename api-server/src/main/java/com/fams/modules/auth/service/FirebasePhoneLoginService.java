package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.request.FirebasePhoneLoginRequest;
import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.entity.RefreshToken;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.shared.exception.InvalidOtpException;
import com.fams.shared.exception.OtpRateLimitException;
import com.fams.shared.security.JwtProvider;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Firebase Phone Auth already gates the OTP code itself — a client cannot obtain a
 * valid Firebase ID token without correctly completing SMS verification with
 * Firebase, so there is no "wrong code" state this endpoint could ever observe.
 * What this endpoint alone can be abused for is being hit repeatedly with
 * garbage/expired tokens or to probe which phone numbers have a FAMS account
 * (the 401 on an unlinked phone), so the Acceptance Criteria's "chặn nhập sai
 * nhiều lần" is enforced here as a per-IP request rate limit instead of a
 * per-attempt failure counter.
 */
@Slf4j
@Service
public class FirebasePhoneLoginService {

    private static final String RATE_LIMIT_PREFIX = "phone-login:rate:";
    private static final int RATE_WINDOW_MINUTES = 15;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRoleRepository userRoleRepository;
    private final JwtProvider jwtProvider;
    private final FirebasePhoneTokenVerifier tokenVerifier;
    private final StringRedisTemplate redis;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;
    private final int rateLimitMax;

    public FirebasePhoneLoginService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            UserRoleRepository userRoleRepository,
            JwtProvider jwtProvider,
            FirebasePhoneTokenVerifier tokenVerifier,
            StringRedisTemplate redis,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes,
            @Value("${app.jwt.refresh-ttl-days}") int refreshTtlDays,
            @Value("${app.otp.rate-limit-max}") int rateLimitMax) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRoleRepository = userRoleRepository;
        this.jwtProvider = jwtProvider;
        this.tokenVerifier = tokenVerifier;
        this.redis = redis;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
        this.rateLimitMax = rateLimitMax;
    }

    @Transactional
    public LoginResponse loginWithPhone(FirebasePhoneLoginRequest request) {
        String rateKey = RATE_LIMIT_PREFIX + HttpRequestUtils.currentIpAddress();
        Long count = redis.opsForValue().increment(rateKey);
        if (count != null && count == 1) {
            redis.expire(rateKey, RATE_WINDOW_MINUTES, TimeUnit.MINUTES);
        }
        if (count != null && count > rateLimitMax) {
            log.warn("Phone login rate limit hit for ip={}", HttpRequestUtils.currentIpAddress());
            throw new OtpRateLimitException();
        }

        String phone = tokenVerifier.verifyAndExtractPhone(request.getFirebaseIdToken());

        User user = userRepository.findByPhoneAndDeletedAtIsNull(phone)
                .orElseThrow(() -> {
                    log.warn("Firebase phone login: no FAMS account linked to phone");
                    return new InvalidOtpException();
                });

        String deviceId = (request.getDeviceId() != null && !request.getDeviceId().isBlank())
                ? request.getDeviceId() : "phone";

        List<UserRole> roles = userRoleRepository.findAllActiveByUserId(user.getId());
        UUID primaryTenantId = roles.isEmpty() ? null : roles.get(0).getTenantId();
        String primaryRole = roles.isEmpty() ? null : roles.get(0).getRole().getName();

        // Same 2FA gate as AuthService.login() — phone login must not let a user with
        // TOTP enabled bypass it entirely. Reuses the exact pending-token format/prefix
        // so the existing POST /auth/login/totp endpoint (LoginTotpService) can complete
        // this login unchanged, regardless of which flow started it.
        if (user.isTotpEnabled()) {
            String pendingToken = UUID.randomUUID().toString();
            String tenantStr = primaryTenantId != null ? primaryTenantId.toString() : "";
            String roleStr = primaryRole != null ? primaryRole : "";
            String value = user.getId() + "|" + user.getEmail() + "|" + deviceId + "|" + user.isPlatformAdmin()
                    + "|" + tenantStr + "|" + roleStr;
            redis.opsForValue().set(AuthService.TOTP_PENDING_PREFIX + pendingToken, value,
                    AuthService.TOTP_PENDING_TTL_MIN, TimeUnit.MINUTES);
            log.info("TOTP required for phone login, user {} — pending token issued", user.getId());
            return LoginResponse.builder()
                    .totpRequired(true)
                    .pendingToken(pendingToken)
                    .build();
        }

        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), deviceId, user.isPlatformAdmin(), primaryTenantId, primaryRole);

        String rawRefresh = jwtProvider.generateRefreshTokenRaw();
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(jwtProvider.hashToken(rawRefresh))
                .deviceId(deviceId)
                .userAgent(HttpRequestUtils.currentUserAgent())
                .ipAddress(HttpRequestUtils.currentIpAddress())
                .expiresAt(OffsetDateTime.now().plusDays(refreshTtlDays))
                .build();
        refreshTokenRepository.save(refreshToken);

        log.info("Firebase phone login successful for user id={}", user.getId());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefresh)
                .tokenType("Bearer")
                .expiresIn((long) accessTtlMinutes * 60)
                .build();
    }
}
