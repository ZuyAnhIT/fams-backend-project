package com.fams.modules.auth.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.auth.dto.request.LoginRequest;
import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.entity.RefreshToken;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.constants.AppConstants;
import com.fams.shared.exception.AccountLockedException;
import com.fams.shared.exception.EmailNotVerifiedException;
import com.fams.shared.exception.InvalidCredentialsException;
import com.fams.shared.exception.TenantSuspendedException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.security.JwtProvider;
import com.fams.shared.security.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

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
    private final TenantRepository tenantRepository;
    private final JwtProvider jwtProvider;
    private final BCryptPasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final AuditLogService auditLogService;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            UserRoleRepository userRoleRepository,
            TenantRepository tenantRepository,
            JwtProvider jwtProvider,
            BCryptPasswordEncoder passwordEncoder,
            StringRedisTemplate redis,
            AuditLogService auditLogService,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes,
            @Value("${app.jwt.refresh-ttl-days}") int refreshTtlDays) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRoleRepository = userRoleRepository;
        this.tenantRepository = tenantRepository;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
        this.auditLogService = auditLogService;
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

        // 6a. Block login if tenant is suspended (platform admins are not tenant-scoped)
        if (!user.isPlatformAdmin() && primaryTenantId != null) {
            tenantRepository.findByIdAndDeletedAtIsNull(primaryTenantId).ifPresent(tenant -> {
                if ("suspended".equals(tenant.getStatus())) {
                    throw new TenantSuspendedException();
                }
            });
        }

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
                .userAgent(HttpRequestUtils.currentUserAgent())
                .ipAddress(HttpRequestUtils.currentIpAddress())
                .expiresAt(OffsetDateTime.now().plusDays(refreshTtlDays))
                .activeTenantId(primaryTenantId)
                .build();
        refreshTokenRepository.save(refreshToken);

        // 9. Record last_login_at + audit LOGIN (Acceptance Criteria, backlog #1)
        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);
        auditLogService.record(
                primaryTenantId,
                user.getId(),
                user.getEmail(),
                "USER",
                user.getId().toString(),
                "LOGIN",
                null,
                null,
                null,
                HttpRequestUtils.currentIpAddress(),
                HttpRequestUtils.currentUserAgent());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .tokenType("Bearer")
                .expiresIn((long) accessTtlMinutes * 60)
                .build();
    }

    /**
     * Issue #3 (docs/issues/ISSUES.md): a user belonging to multiple tenants needs a way to
     * actually operate as a different company — until now, login (and every refresh) always
     * re-picked the OLDEST role assignment, so there was no real "switch company" possible.
     * This re-issues a token pair scoped to {@code request.tenantId} (which the caller must
     * hold an active role in) and persists that choice onto the refresh-token row so it
     * survives subsequent silent token refreshes ({@link RefreshTokenService#refresh}).
     */
    @Transactional
    public LoginResponse switchTenant(UUID callerUserId, UUID targetTenantId, String rawRefreshToken) {
        User user = userRepository.findByIdAndDeletedAtIsNull(callerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String hash = jwtProvider.hashToken(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
        if (!stored.getUser().getId().equals(callerUserId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token does not belong to this user");
        }
        if (stored.getRevokedAt() != null || OffsetDateTime.now().isAfter(stored.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token is no longer valid");
        }

        List<UserRole> targetRoles = userRoleRepository.findActiveByUserIdAndTenantId(callerUserId, targetTenantId);
        if (targetRoles.isEmpty()) {
            throw new AccessDeniedException("You do not have a role in this company");
        }
        String targetRole = targetRoles.get(0).getRole().getName();

        tenantRepository.findByIdAndDeletedAtIsNull(targetTenantId).ifPresentOrElse(tenant -> {
            if ("suspended".equals(tenant.getStatus())) {
                throw new TenantSuspendedException();
            }
        }, () -> {
            throw new ResourceNotFoundException("Tenant not found: " + targetTenantId);
        });

        String accessToken = jwtProvider.generateAccessToken(
                user.getId(), user.getEmail(), stored.getDeviceId(),
                user.isPlatformAdmin(), targetTenantId, targetRole);

        // Rotate the refresh token (same convention as RefreshTokenService.refresh), carrying
        // the newly-chosen tenant forward so it sticks across future refreshes.
        stored.setRevokedAt(OffsetDateTime.now());
        refreshTokenRepository.save(stored);

        String rawNew = jwtProvider.generateRefreshTokenRaw();
        RefreshToken newToken = RefreshToken.builder()
                .user(user)
                .tokenHash(jwtProvider.hashToken(rawNew))
                .deviceId(stored.getDeviceId())
                .userAgent(HttpRequestUtils.currentUserAgent())
                .ipAddress(HttpRequestUtils.currentIpAddress())
                .expiresAt(OffsetDateTime.now().plusDays(refreshTtlDays))
                .activeTenantId(targetTenantId)
                .build();
        refreshTokenRepository.save(newToken);

        log.info("User {} switched active tenant to {}", callerUserId, targetTenantId);

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawNew)
                .tokenType("Bearer")
                .expiresIn((long) accessTtlMinutes * 60)
                .build();
    }
}
