package com.fams.modules.auth.service;

import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.entity.RefreshToken;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.exception.TenantSuspendedException;
import com.fams.shared.security.JwtProvider;
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
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final TenantRepository tenantRepository;
    private final JwtProvider jwtProvider;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            TenantRepository tenantRepository,
            JwtProvider jwtProvider,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes,
            @Value("${app.jwt.refresh-ttl-days}") int refreshTtlDays) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.tenantRepository = tenantRepository;
        this.jwtProvider = jwtProvider;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
    }

    @Transactional
    public LoginResponse refresh(String rawRefreshToken) {
        String hash = jwtProvider.hashToken(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        if (stored.getRevokedAt() != null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token has been revoked");
        }
        if (OffsetDateTime.now().isAfter(stored.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token has expired");
        }

        User user = userRepository.findById(stored.getUser().getId())
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is inactive");
        }

        List<UserRole> roles = userRoleRepository.findAllActiveByUserId(user.getId());
        UUID primaryTenantId = roles.isEmpty() ? null : roles.get(0).getTenantId();
        String primaryRole = roles.isEmpty() ? null : roles.get(0).getRole().getName();

        if (!user.isPlatformAdmin() && primaryTenantId != null) {
            tenantRepository.findByIdAndDeletedAtIsNull(primaryTenantId).ifPresent(tenant -> {
                if ("suspended".equals(tenant.getStatus())) {
                    throw new TenantSuspendedException();
                }
            });
        }

        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), stored.getDeviceId(),
                user.isPlatformAdmin(), primaryTenantId, primaryRole);

        // Rotate: revoke old refresh token, issue a new one
        stored.setRevokedAt(OffsetDateTime.now());
        refreshTokenRepository.save(stored);

        String rawNew = jwtProvider.generateRefreshTokenRaw();
        RefreshToken newToken = RefreshToken.builder()
                .user(user)
                .tokenHash(jwtProvider.hashToken(rawNew))
                .deviceId(stored.getDeviceId())
                .expiresAt(OffsetDateTime.now().plusDays(refreshTtlDays))
                .build();
        refreshTokenRepository.save(newToken);

        log.info("Refresh token rotated for userId={}", user.getId());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawNew)
                .tokenType("Bearer")
                .expiresIn((long) accessTtlMinutes * 60)
                .build();
    }
}
