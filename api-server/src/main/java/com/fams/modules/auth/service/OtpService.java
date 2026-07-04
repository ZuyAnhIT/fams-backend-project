package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.request.SendOtpRequest;
import com.fams.modules.auth.dto.request.VerifyOtpRequest;
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
import com.fams.shared.sms.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OtpService {

    private static final String OTP_CODE_PREFIX = "otp:code:";
    private static final String OTP_RATE_PREFIX = "otp:rate:";
    private static final int OTP_TTL_MINUTES = 5;
    private static final int RATE_WINDOW_MINUTES = 10;

    private final StringRedisTemplate redis;
    private final SmsService smsService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRoleRepository userRoleRepository;
    private final JwtProvider jwtProvider;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;
    private final String devFixedCode;
    private final int rateLimitMax;

    public OtpService(
            StringRedisTemplate redis,
            SmsService smsService,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            UserRoleRepository userRoleRepository,
            JwtProvider jwtProvider,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes,
            @Value("${app.jwt.refresh-ttl-days}") int refreshTtlDays,
            @Value("${app.otp.dev-fixed-code:}") String devFixedCode,
            @Value("${app.otp.rate-limit-max:3}") int rateLimitMax) {
        this.redis = redis;
        this.smsService = smsService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRoleRepository = userRoleRepository;
        this.jwtProvider = jwtProvider;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
        this.devFixedCode = devFixedCode;
        this.rateLimitMax = rateLimitMax;
    }

    public void sendOtp(SendOtpRequest request) {
        String phone = request.getPhone();
        String rateKey = OTP_RATE_PREFIX + phone;

        Long count = redis.opsForValue().increment(rateKey);
        if (count != null && count == 1) {
            redis.expire(rateKey, RATE_WINDOW_MINUTES, TimeUnit.MINUTES);
        }
        if (count != null && count > rateLimitMax) {
            throw new OtpRateLimitException();
        }

        String otp = generateOtp();
        String codeKey = OTP_CODE_PREFIX + phone;
        redis.opsForValue().set(codeKey, otp, OTP_TTL_MINUTES, TimeUnit.MINUTES);

        // Only send SMS if user exists — but don't expose this to caller
        Optional<User> userOpt = userRepository.findByPhoneAndDeletedAtIsNull(phone);
        if (userOpt.isPresent()) {
            smsService.sendOtp(phone, otp);
        } else {
            log.debug("OTP send requested for unknown phone — silently discarded");
        }
    }

    @Transactional
    public LoginResponse verifyOtp(VerifyOtpRequest request) {
        String phone = request.getPhone();
        String codeKey = OTP_CODE_PREFIX + phone;

        String stored = redis.opsForValue().get(codeKey);
        if (stored == null || !stored.equals(request.getCode())) {
            throw new InvalidOtpException();
        }

        redis.delete(codeKey);

        User user = userRepository.findByPhoneAndDeletedAtIsNull(phone)
                .orElseThrow(InvalidOtpException::new);

        String deviceId = (request.getDeviceId() != null) ? request.getDeviceId() : "unknown";
        List<UserRole> roles = userRoleRepository.findAllActiveByUserId(user.getId());
        UUID primaryTenantId = roles.isEmpty() ? null : roles.get(0).getTenantId();
        String primaryRole = roles.isEmpty() ? null : roles.get(0).getRole().getName();
        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), deviceId, user.isPlatformAdmin(), primaryTenantId, primaryRole);

        String rawRefresh = jwtProvider.generateRefreshTokenRaw();
        String tokenHash = jwtProvider.hashToken(rawRefresh);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .deviceId(deviceId)
                .expiresAt(OffsetDateTime.now().plusDays(refreshTtlDays))
                .build();
        refreshTokenRepository.save(refreshToken);

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefresh)
                .tokenType("Bearer")
                .expiresIn((long) accessTtlMinutes * 60)
                .build();
    }

    private String generateOtp() {
        if (devFixedCode != null && !devFixedCode.isBlank()) {
            return devFixedCode;
        }
        SecureRandom random = new SecureRandom();
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
