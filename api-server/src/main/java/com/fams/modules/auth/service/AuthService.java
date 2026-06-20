package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.request.LoginRequest;
import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.entity.RefreshToken;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.shared.constants.AppConstants;
import com.fams.shared.exception.AccountLockedException;
import com.fams.shared.exception.InvalidCredentialsException;
import com.fams.shared.security.JwtProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Slf4j
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final BCryptPasswordEncoder passwordEncoder;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtProvider jwtProvider,
            BCryptPasswordEncoder passwordEncoder,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes,
            @Value("${app.jwt.refresh-ttl-days}") int refreshTtlDays) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
    }

    @Transactional
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

        // 4. Success: reset failed attempts
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        // 5. Generate access token
        String deviceId = (request.getDeviceId() != null) ? request.getDeviceId() : "unknown";
        String accessToken = jwtProvider.generateAccessToken(user.getId(), user.getEmail(), deviceId, user.isPlatformAdmin());

        // 6. Generate refresh token
        String rawRefreshToken = jwtProvider.generateRefreshTokenRaw();
        String tokenHash = jwtProvider.hashToken(rawRefreshToken);

        // 7. Save refresh token
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .deviceId(deviceId)
                .expiresAt(OffsetDateTime.now().plusDays(refreshTtlDays))
                .build();
        refreshTokenRepository.save(refreshToken);

        // 8. Return response
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .tokenType("Bearer")
                .expiresIn((long) accessTtlMinutes * 60)
                .build();
    }
}
