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
import com.fams.shared.security.JwtProvider;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class FirebasePhoneLoginService {

    private static final String PHONE_NUMBER_CLAIM = "phone_number";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRoleRepository userRoleRepository;
    private final JwtProvider jwtProvider;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public FirebasePhoneLoginService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            UserRoleRepository userRoleRepository,
            JwtProvider jwtProvider,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes,
            @Value("${app.jwt.refresh-ttl-days}") int refreshTtlDays) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRoleRepository = userRoleRepository;
        this.jwtProvider = jwtProvider;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
    }

    @Transactional
    public LoginResponse loginWithPhone(FirebasePhoneLoginRequest request) {
        FirebaseToken decodedToken = verifyFirebaseToken(request.getFirebaseIdToken());

        String phone = (String) decodedToken.getClaims().get(PHONE_NUMBER_CLAIM);
        if (phone == null || phone.isBlank()) {
            log.warn("Firebase ID token does not contain a phone_number claim — not a phone auth token");
            throw new InvalidOtpException();
        }

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

        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), deviceId, user.isPlatformAdmin(), primaryTenantId, primaryRole);

        String rawRefresh = jwtProvider.generateRefreshTokenRaw();
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(jwtProvider.hashToken(rawRefresh))
                .deviceId(deviceId)
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

    private FirebaseToken verifyFirebaseToken(String idToken) {
        if (FirebaseApp.getApps().isEmpty()) {
            log.error("Firebase is not initialized — FCM_PROJECT_ID and FCM_SERVICE_ACCOUNT_JSON must be set");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Firebase authentication is not configured on this server");
        }
        try {
            return FirebaseAuth.getInstance().verifyIdToken(idToken);
        } catch (FirebaseAuthException e) {
            log.warn("Firebase ID token verification failed: {}", e.getMessage());
            throw new InvalidOtpException();
        }
    }
}
