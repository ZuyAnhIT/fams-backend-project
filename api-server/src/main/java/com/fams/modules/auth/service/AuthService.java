package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.request.LoginRequest;
import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.entity.RefreshToken;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.shared.constants.AppConstants;
import com.fams.shared.exception.AccountLockedException;
import com.fams.shared.exception.EmailNotVerifiedException;
import com.fams.shared.exception.InvalidCredentialsException;
import com.fams.shared.security.JwtProvider;
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
public class AuthService {

    static final String TOTP_PENDING_PREFIX  = "login:totp:pending:";
    static final int    TOTP_PENDING_TTL_MIN = 5;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRoleRepository userRoleRepository;
    private final JwtProvider jwtProvider;
    private final BCryptPasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            UserRoleRepository userRoleRepository,
            JwtProvider jwtProvider,
            BCryptPasswordEncoder passwordEncoder,
            StringRedisTemplate redis,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes,
            @Value("${app.jwt.refresh-ttl-days}") int refreshTtlDays) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRoleRepository = userRoleRepository;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
    }

    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class})
    public LoginResponse login(LoginRequest request) {
        // 1. Find user by email
        User user = userRepository.findByEmailAndDeletedAtIsNull(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        // 2. Check if account is locked
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(OffsetDateTime.now())) {
            throw new AccountLockedException(user.getLockedUntil());
        }

        // 3. Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);

            if (attempts >= AppConstants.MAX_FAILED_ATTEMPTS) {
                OffsetDateTime lockUntil = OffsetDateTime.now()
                        .plusMinutes(AppConstants.LOCK_DURATION_MINUTES);
                user.setLockedUntil(lockUntil);
                userRepository.save(user);
                log.warn("Account locked for user {} until {}", user.getEmail(), lockUntil);
                throw new AccountLockedException(lockUntil);
            }

            userRepository.save(user);
            throw new InvalidCredentialsException("Invalid email or password");
        }

        // 4. Check email verification (only for email-based accounts)
        if (user.getEmail() != null && !user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }

        // 5. Reset failed attempts
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        // 6. Resolve primary tenant role for JWT claims
        List<UserRole> roles = userRoleRepository.findAllActiveByUserId(user.getId());
        UUID primaryTenantId = roles.isEmpty() ? null : roles.get(0).getTenantId();
        String primaryRole = roles.isEmpty() ? null : roles.get(0).getRole().getName();

        // 7. If TOTP is enabled, issue a pending token instead of real tokens
        String deviceId = (request.getDeviceId() != null) ? request.getDeviceId() : "unknown";
        if (user.isTotpEnabled()) {
            String pendingToken = UUID.randomUUID().toString();
            // Store: userId|email|deviceId|isPlatformAdmin|tenantId|role
            String tenantStr = primaryTenantId != null ? primaryTenantId.toString() : "";
            String roleStr = primaryRole != null ? primaryRole : "";
            String value = user.getId() + "|" + user.getEmail() + "|" + deviceId + "|" + user.isPlatformAdmin()
                    + "|" + tenantStr + "|" + roleStr;
            redis.opsForValue().set(TOTP_PENDING_PREFIX + pendingToken, value, TOTP_PENDING_TTL_MIN, TimeUnit.MINUTES);
            log.info("TOTP required for user {} — pending token issued", user.getEmail());
            return LoginResponse.builder()
                    .totpRequired(true)
                    .pendingToken(pendingToken)
                    .build();
        }

        // 8. Generate access token
        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), deviceId, user.isPlatformAdmin(), primaryTenantId, primaryRole);

        // 8. Generate and save refresh token
        String rawRefreshToken = jwtProvider.generateRefreshTokenRaw();
        String tokenHash = jwtProvider.hashToken(rawRefreshToken);
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .deviceId(deviceId)
                .expiresAt(OffsetDateTime.now().plusDays(refreshTtlDays))
                .build();
        refreshTokenRepository.save(refreshToken);

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .tokenType("Bearer")
                .expiresIn((long) accessTtlMinutes * 60)
                .build();
    }
}
