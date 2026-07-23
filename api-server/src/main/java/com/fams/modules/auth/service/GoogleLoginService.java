package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.request.GoogleLoginRequest;
import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.entity.RefreshToken;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.security.JwtProvider;
import com.fams.shared.security.HttpRequestUtils;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class GoogleLoginService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRoleRepository userRoleRepository;
    private final JwtProvider jwtProvider;
    private final GoogleIdTokenVerifier verifier;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public GoogleLoginService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            UserRoleRepository userRoleRepository,
            JwtProvider jwtProvider,
            @Value("${app.google.client-id}") String googleClientId,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes,
            @Value("${app.jwt.refresh-ttl-days}") int refreshTtlDays) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRoleRepository = userRoleRepository;
        this.jwtProvider = jwtProvider;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(googleClientId))
                .build();
    }

    @Transactional
    public LoginResponse loginWithGoogle(GoogleLoginRequest request) {
        GoogleIdToken.Payload payload = verifyToken(request.getIdToken());

        String googleId = payload.getSubject();
        String email = payload.getEmail();
        String name = (String) payload.get("name");
        String picture = (String) payload.get("picture");

        User user = userRepository.findByGoogleIdAndDeletedAtIsNull(googleId)
                .orElseGet(() -> findOrCreateByEmail(googleId, email, name, picture));

        if (user.getGoogleId() == null) {
            user.setGoogleId(googleId);
            if (picture != null && user.getAvatarUrl() == null) {
                user.setAvatarUrl(picture);
            }
            userRepository.save(user);
        }

        String deviceId = (request.getDeviceId() != null) ? request.getDeviceId() : "google";
        List<UserRole> roles = userRoleRepository.findAllActiveByUserId(user.getId());
        UUID primaryTenantId = roles.isEmpty() ? null : roles.get(0).getTenantId();
        String primaryRole = roles.isEmpty() ? null : roles.get(0).getRole().getName();
        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), deviceId, user.isPlatformAdmin(), primaryTenantId, primaryRole);

        String rawRefreshToken = jwtProvider.generateRefreshTokenRaw();
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(jwtProvider.hashToken(rawRefreshToken))
                .deviceId(deviceId)
                .userAgent(HttpRequestUtils.currentUserAgent())
                .ipAddress(HttpRequestUtils.currentIpAddress())
                .expiresAt(OffsetDateTime.now().plusDays(refreshTtlDays))
                .build();
        refreshTokenRepository.save(refreshToken);

        log.info("Google login successful for user id={}", user.getId());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .tokenType("Bearer")
                .expiresIn((long) accessTtlMinutes * 60)
                .build();
    }

    /**
     * Issue #7 (docs/issues/ISSUES.md): explicit "connect my Google account" action for a
     * user who is ALREADY logged in via email+password — rather than relying only on the
     * implicit auto-link that happens the next time they use "Login with Google" with a
     * matching email (still supported, unchanged, in {@link #loginWithGoogle}). Rejects if
     * this Google account is already linked to a different FAMS user.
     */
    @Transactional
    public void linkGoogleAccount(UUID userId, String idToken) {
        GoogleIdToken.Payload payload = verifyToken(idToken);
        String googleId = payload.getSubject();

        userRepository.findByGoogleIdAndDeletedAtIsNull(googleId).ifPresent(existing -> {
            if (!existing.getId().equals(userId)) {
                throw new DuplicateResourceException("This Google account is already linked to a different user");
            }
        });

        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setGoogleId(googleId);
        userRepository.save(user);
        log.info("Google account linked for user id={}", userId);
    }

    /**
     * Issue #7 (docs/issues/ISSUES.md): the reverse of linking. Requires the account to
     * already have a password set — otherwise unlinking would leave the user with zero
     * ways to prove identity on demand (they could still recover via forgot-password, but
     * requiring a password first matches how most real systems guard against removing your
     * only sign-in method).
     */
    @Transactional
    public void unlinkGoogleAccount(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getPasswordHash() == null) {
            throw new IllegalStateException(
                    "Set a password first (via Change Password or Forgot Password) before unlinking Google — "
                            + "otherwise you would have no way to log in.");
        }
        user.setGoogleId(null);
        userRepository.save(user);
        log.info("Google account unlinked for user id={}", userId);
    }

    private User findOrCreateByEmail(String googleId, String email, String name, String picture) {
        return userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseGet(() -> {
                    String displayName = (name != null && !name.isBlank()) ? name : email;
                    User newUser = User.builder()
                            .email(email)
                            .googleId(googleId)
                            .displayName(displayName)
                            .avatarUrl(picture)
                            .isActive(true)
                            .emailVerified(true)
                            .failedLoginAttempts(0)
                            .isPlatformAdmin(false)
                            .build();
                    userRepository.save(newUser);
                    log.info("New user created via Google login: id={}", newUser.getId());
                    return newUser;
                });
    }

    private GoogleIdToken.Payload verifyToken(String idToken) {
        try {
            GoogleIdToken googleIdToken = verifier.verify(idToken);
            if (googleIdToken == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired Google ID token");
            }
            return googleIdToken.getPayload();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Google token verification failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired Google ID token");
        }
    }
}
