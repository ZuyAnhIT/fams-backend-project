package com.fams.modules.auth.service;

import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.auth.dto.request.GoogleLoginRequest;
import com.fams.modules.auth.dto.response.LoginResponse;
import com.fams.modules.auth.entity.RefreshToken;
import com.fams.modules.auth.entity.User;
import com.fams.modules.auth.repository.RefreshTokenRepository;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.rbac.entity.UserRole;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.DuplicateResourceException;
import com.fams.shared.exception.InvalidCredentialsException;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.exception.TenantSuspendedException;
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
    private final TenantRepository tenantRepository;
    private final JwtProvider jwtProvider;
    private final AuditLogService auditLogService;
    private final GoogleIdTokenVerifier verifier;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public GoogleLoginService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            UserRoleRepository userRoleRepository,
            TenantRepository tenantRepository,
            JwtProvider jwtProvider,
            AuditLogService auditLogService,
            @Value("${app.google.client-id}") String googleClientId,
            @Value("${app.jwt.access-ttl-minutes}") int accessTtlMinutes,
            @Value("${app.jwt.refresh-ttl-days}") int refreshTtlDays) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRoleRepository = userRoleRepository;
        this.tenantRepository = tenantRepository;
        this.jwtProvider = jwtProvider;
        this.auditLogService = auditLogService;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(googleClientId))
                .build();
    }

    /**
     * Đăng nhập bằng Google — hỗ trợ đồng bộ 2 chiều với tài khoản email/password:
     *
     *  Chiều 1 (Google trước, password sau): User chưa từng có tài khoản → tạo mới,
     *    passwordHash=null. Lần sau muốn login bằng password, user phải qua
     *    "Quên mật khẩu" để set password lần đầu (flow đã có sẵn, không cần code thêm).
     *
     *  Chiều 2 (password trước, Google sau): User đã có tài khoản email+password,
     *    login Google lần đầu bằng đúng email đó → tự động LINK (không tạo tài khoản
     *    mới), đồng thời set email_verified=true nếu trước đó chưa verify — vì Google
     *    đã đảm bảo user sở hữu email này, việc yêu cầu verify lại qua email là thừa
     *    và sẽ khiến 2 kênh đăng nhập bị lệch trạng thái với nhau.
     */
    @Transactional
    public LoginResponse loginWithGoogle(GoogleLoginRequest request) {
        GoogleIdToken.Payload payload = verifyToken(request.getIdToken());

        String googleId = payload.getSubject();
        String email = payload.getEmail();
        String name = (String) payload.get("name");
        String picture = (String) payload.get("picture");

        // Google tự xác minh quyền sở hữu email trước khi cấp token, nhưng field
        // này vẫn tồn tại cho các trường hợp hiếm (email nội bộ tổ chức/G Suite
        // chưa hoàn tất xác minh). Không nên tin tưởng email chưa được Google
        // xác nhận để tạo tài khoản hoặc auto-link — đây là điểm chèn tài khoản
        // (account takeover) nếu bỏ qua.
        Boolean emailVerifiedByGoogle = payload.getEmailVerified();
        if (email != null && !Boolean.TRUE.equals(emailVerifiedByGoogle)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Email của tài khoản Google này chưa được Google xác thực");
        }

        User user = userRepository.findByGoogleIdAndDeletedAtIsNull(googleId)
                .orElseGet(() -> findOrCreateByEmail(googleId, email, name, picture));

        // ── Auto-link: user tồn tại (đăng ký thường trước) nhưng chưa gắn Google ──
        if (user.getGoogleId() == null) {
            user.setGoogleId(googleId);
            if (picture != null && user.getAvatarUrl() == null) {
                user.setAvatarUrl(picture);
            }
            // ĐỒNG BỘ QUAN TRỌNG: nếu account đăng ký thường nhưng chưa verify email,
            // Google login chứng minh user sở hữu email này rồi → tự động verify luôn,
            // để không bị kẹt "email chưa xác thực" khi quay lại login bằng password.
            if (!user.isEmailVerified()) {
                user.setEmailVerified(true);
                log.info("Email auto-verified via Google link for user id={}", user.getId());
            }
            userRepository.save(user);
            log.info("Google account linked (auto) to existing user id={}", user.getId());

            recordAudit(null, user, "GOOGLE_AUTO_LINK");
        }

        // ── Kiểm tra tài khoản active — đồng bộ với AuthService.login() ──────────
        if (!user.isActive()) {
            throw new InvalidCredentialsException("Account is not active");
        }

        // ── Resolve tenant & kiểm tra suspended — đồng bộ với AuthService.login() ─
        List<UserRole> roles = userRoleRepository.findAllActiveByUserId(user.getId());
        UserRole primary = com.fams.modules.rbac.util.PrimaryRoleResolver.pickPrimary(roles);
        UUID primaryTenantId = user.isPlatformAdmin() || primary == null ? null : primary.getTenantId();
        String primaryRole = user.isPlatformAdmin() || primary == null ? null : primary.getRole().getName();

        if (!user.isPlatformAdmin() && primaryTenantId != null) {
            tenantRepository.findByIdAndDeletedAtIsNull(primaryTenantId).ifPresent(tenant -> {
                if ("suspended".equals(tenant.getStatus())) {
                    throw new TenantSuspendedException();
                }
            });
        }

        // Ghi chú: Google login KHÔNG bị chặn bởi TOTP hoặc bởi lockedUntil (khóa do
        // sai password nhiều lần) — đây là quyết định thiết kế nhất quán với đa số
        // hệ thống thực tế (Slack, Notion, Zoom...): đăng nhập qua nhà cung cấp OAuth
        // được xem là một yếu tố xác thực độc lập, mạnh tương đương, nên không áp
        // dụng lại các rào chắn dành riêng cho kênh password.

        String deviceId = (request.getDeviceId() != null) ? request.getDeviceId() : "google";
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
                .activeTenantId(primaryTenantId)
                .build();
        refreshTokenRepository.save(refreshToken);

        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);

        recordAudit(primaryTenantId, user, "LOGIN");

        log.info("Google login successful for user id={}", user.getId());

        return LoginResponse.builder()
                .userId(user.getId())
                .activeTenantId(primaryTenantId)
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

        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Email của tài khoản Google này chưa được Google xác thực");
        }

        userRepository.findByGoogleIdAndDeletedAtIsNull(googleId).ifPresent(existing -> {
            if (!existing.getId().equals(userId)) {
                throw new DuplicateResourceException("This Google account is already linked to a different user");
            }
        });

        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setGoogleId(googleId);
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
        }
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

    // ════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ════════════════════════════════════════════════════════════════════════

    private User findOrCreateByEmail(String googleId, String email, String name, String picture) {
        return userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseGet(() -> {
                    // Lấy thông tin sẵn có từ Google, phone để trống — bổ sung sau
                    // qua tính năng cập nhật hồ sơ cá nhân.
                    String displayName = (name != null && !name.isBlank()) ? name : email;
                    User newUser = User.builder()
                            .email(email)
                            .googleId(googleId)
                            .displayName(displayName)
                            .avatarUrl(picture)
                            .isActive(true)
                            .emailVerified(true) // Google đã xác thực email, không cần verify lại
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

    private void recordAudit(UUID tenantId, User user, String action) {
        try {
            auditLogService.record(
                    tenantId,
                    user.getId(),
                    user.getEmail() != null ? user.getEmail() : user.getPhone(),
                    "USER",
                    user.getId().toString(),
                    action,
                    null,
                    null,
                    HttpRequestUtils.currentRequestId(),
                    HttpRequestUtils.currentIpAddress(),
                    HttpRequestUtils.currentUserAgent());
        } catch (Exception ex) {
            // Audit không được làm hỏng flow login chính
            log.warn("Failed to record audit action={} for user={}: {}", action, user.getId(), ex.getMessage());
        }
    }
}
