package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.request.RegisterRequest;
import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.entity.RefreshToken;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.security.JwtProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;

@Slf4j
@Service
public class RegisterService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final BCryptPasswordEncoder passwordEncoder;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public RegisterService(
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
    public LoginResponse register(RegisterRequest request) {
        String email = StringUtils.hasText(request.getEmail()) ? request.getEmail().trim() : null;
        String phone = StringUtils.hasText(request.getPhone()) ? request.getPhone().trim() : null;

        if (email == null && phone == null) {
            throw new IllegalArgumentException("At least one of email or phone is required");
        }

        if (email != null && userRepository.findByEmailAndDeletedAtIsNull(email).isPresent()) {
            throw new DuplicateResourceException("Email is already registered");
        }

        if (phone != null && userRepository.findByPhoneAndDeletedAtIsNull(phone).isPresent()) {
            throw new DuplicateResourceException("Phone number is already registered");
        }

        User user = User.builder()
                .email(email)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .displayName(request.getDisplayName())
                .isActive(true)
                .failedLoginAttempts(0)
                .build();
        userRepository.save(user);
        log.info("New user registered: id={}", user.getId());

        String deviceId = StringUtils.hasText(request.getDeviceId()) ? request.getDeviceId() : "unknown";
        String accessToken = jwtProvider.generateAccessToken(user.getId(), email, deviceId, false);

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
}
