package com.fams.shared.security;

import com.fams.modules.auth.service.LogoutService;
import com.fams.modules.rbac.repository.PermissionRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.tenant.service.IpWhitelistGuard;
import com.fams.modules.tenant.service.TenantService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    // Bump the namespace whenever a migration changes system-role grants. V124 separates
    // list/view from review/adjust, so reusing the old cached set would temporarily preserve
    // stale privilege until TTL expiry after a deployment.
    public static final String PERMS_CACHE_PREFIX = "auth:perms:v124:";
    static final String PLATFORM_ADMIN_PERMS_KEY = PERMS_CACHE_PREFIX + "platform_admin";
    private static final long PERMS_CACHE_TTL_SECONDS = 300; // 5 min

    private final JwtProvider jwtProvider;
    private final StringRedisTemplate redis;
    private final UserRoleRepository userRoleRepository;
    private final PermissionRepository permissionRepository;
    private final IpWhitelistGuard ipWhitelistGuard;

    public JwtAuthFilter(JwtProvider jwtProvider,
                         StringRedisTemplate redis,
                         UserRoleRepository userRoleRepository,
                         PermissionRepository permissionRepository,
                         IpWhitelistGuard ipWhitelistGuard) {
        this.jwtProvider = jwtProvider;
        this.redis = redis;
        this.userRoleRepository = userRoleRepository;
        this.permissionRepository = permissionRepository;
        this.ipWhitelistGuard = ipWhitelistGuard;
    }

    /** Same X-Forwarded-For-aware extraction as HttpRequestUtils.currentIpAddress(), but
     *  reading straight from the filter's own request parameter — RequestContextHolder
     *  isn't reliably populated this early in the chain (before DispatcherServlet). */
    private String clientIpFrom(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Public (permitAll — see SecurityConfig) endpoints that this filter must skip entirely.
     * Bug fixed here: this filter used to run unconditionally on EVERY request and, whenever
     * an Authorization header happened to be present — even a stale/irrelevant one left over
     * from a previous session by a frontend client that blindly re-attaches the last stored
     * token — it would resolve that token's tenantId claim and enforce THAT tenant's
     * suspended/IP-whitelist checks against the CURRENT request. That 403'd /auth/login,
     * /auth/register, and /auth/login/google outright (with "IP not whitelisted") for anyone
     * whose browser still had an old access token in storage for a tenant with IP whitelist
     * entries, regardless of which account they were actually trying to log into — login,
     * registration, and Google sign-in for a brand-new account have no tenant yet and must
     * never be gated by a leftover, request-irrelevant Bearer token. These endpoints don't
     * need SecurityContext authentication at all (they're permitAll), so skipping this filter
     * for them is safe and matches intent. Keep this list in sync with SecurityConfig's
     * permitAll() matchers.
     */
    private static final Set<String> PUBLIC_EXACT_PATHS = Set.of(
            "/api/v1/auth/health", "/api/v1/auth/db-health", "/api/v1/auth/totp/qr",
            "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/register/send-otp",
            "/api/v1/auth/resend-verification", "/api/v1/auth/otp/verify",
            "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
            "/api/v1/auth/login/totp", "/api/v1/auth/login/google", "/api/v1/auth/refresh-token",
            "/api/v1/payments/payos/webhook",
            "/api/v1/invitations/validate", "/api/v1/invitations/accept",
            "/api/v1/platform-invitations/validate", "/api/v1/platform-invitations/accept",
            "/google-login-test.html", "/actuator/health", "/actuator/info"
    );
    private static final String[] PUBLIC_PATH_PREFIXES = {
            "/v3/api-docs/", "/swagger-ui/", "/internal/"
    };

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (PUBLIC_EXACT_PATHS.contains(path) || "/swagger-ui.html".equals(path)) {
            return true;
        }
        // The plan catalogue is public for checkout, but plan administration is not.
        // Skipping every HTTP method here would prevent JWT authentication on POST/PATCH
        // and make authenticated Platform Admin writes fail with 401.
        if ("GET".equalsIgnoreCase(request.getMethod())
                && ("/api/v1/plans".equals(path) || path.startsWith("/api/v1/plans/"))) {
            return true;
        }
        for (String prefix : PUBLIC_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTenantSuspended(String tenantId) {
        if (tenantId == null || tenantId.isEmpty()) return false;
        return Boolean.TRUE.equals(redis.hasKey(TenantService.TENANT_SUSPENDED_PREFIX + tenantId));
    }

    private boolean isBillingRecoveryPath(HttpServletRequest request) {
        return request.getServletPath().matches(
                "/api/v1/tenants/[^/]+/(?:billing-orders(?:/.*)?|subscription)");
    }

    private Set<String> loadPlatformAdminPermissions() {
        String cached = redis.opsForValue().get(PLATFORM_ADMIN_PERMS_KEY);
        if (cached != null && !cached.isEmpty()) {
            return new HashSet<>(Arrays.asList(cached.split(",")));
        }
        Set<String> perms = permissionRepository.findAllByOrderByResourceAscActionAsc()
                .stream()
                .map(p -> p.getName())
                .collect(Collectors.toSet());
        if (!perms.isEmpty()) {
            redis.opsForValue().set(PLATFORM_ADMIN_PERMS_KEY,
                    String.join(",", perms), PERMS_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        }
        return perms;
    }

    private Set<String> loadUserPermissions(UUID userId, String tenantId) {
        Set<String> platformScoped = loadPlatformScopedPermissions(userId);

        if (tenantId == null || tenantId.isEmpty()) return platformScoped;

        String cacheKey = PERMS_CACHE_PREFIX + userId + ":" + tenantId;
        String cached = redis.opsForValue().get(cacheKey);
        Set<String> tenantScoped;
        if (cached != null) {
            tenantScoped = cached.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(cached.split(",")));
        } else {
            tenantScoped = userRoleRepository.findPermissionNamesByUserIdAndTenantId(
                    userId, UUID.fromString(tenantId));
            redis.opsForValue().set(cacheKey,
                    String.join(",", tenantScoped), PERMS_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        }

        if (platformScoped.isEmpty()) return tenantScoped;
        Set<String> merged = new HashSet<>(tenantScoped);
        merged.addAll(platformScoped);
        return merged;
    }

    /**
     * Issue #10 (docs/issues/ISSUES.md): permissions from platform-scoped role assignments
     * (e.g. PLATFORM_STAFF) apply regardless of which tenant (if any) the request is scoped to.
     * Empty for the overwhelming majority of users who have no such assignment — this only ever
     * adds permissions, never removes any tenant-scoped ones.
     */
    private Set<String> loadPlatformScopedPermissions(UUID userId) {
        String cacheKey = PERMS_CACHE_PREFIX + userId + ":platform";
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            if (cached.isEmpty()) return Set.of();
            return new HashSet<>(Arrays.asList(cached.split(",")));
        }
        Set<String> perms = userRoleRepository.findPlatformScopedPermissionNamesByUserId(userId);
        redis.opsForValue().set(cacheKey,
                String.join(",", perms), PERMS_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        return perms;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearerToken(request);

        if (StringUtils.hasText(token)) {
            String blacklistKey = LogoutService.BLACKLIST_PREFIX + jwtProvider.hashToken(token);
            if (Boolean.TRUE.equals(redis.hasKey(blacklistKey))) {
                log.debug("JWT token is blacklisted — skipping authentication");
                filterChain.doFilter(request, response);
                return;
            }

            try {
                Claims claims = jwtProvider.parseAccessToken(token);
                UUID userId = UUID.fromString(claims.getSubject());
                String deviceId = claims.get("deviceId", String.class);

                String userRevokeKey = LogoutService.USER_REVOKE_PREFIX + userId;
                String revokeTimestampStr = redis.opsForValue().get(userRevokeKey);
                if (revokeTimestampStr != null) {
                    long storedRevokeTimestamp = Long.parseLong(revokeTimestampStr);
                    // Backward-compatible with second-resolution values left in Redis by a
                    // pre-upgrade process; all newly written values are milliseconds.
                    long revokeMillis = storedRevokeTimestamp < 100_000_000_000L
                            ? storedRevokeTimestamp * 1000
                            : storedRevokeTimestamp;
                    Number issuedAtMillisClaim = claims.get("issuedAtMillis", Number.class);
                    long tokenIssuedAtMillis = issuedAtMillisClaim != null
                            ? issuedAtMillisClaim.longValue()
                            : claims.getIssuedAt().getTime();
                    if (tokenIssuedAtMillis <= revokeMillis) {
                        log.debug("Token predates logout-all/change-password event — skipping authentication");
                        filterChain.doFilter(request, response);
                        return;
                    }
                }

                String deviceRevokeKey = LogoutService.DEVICE_REVOKE_PREFIX + userId + ":" + deviceId;
                String deviceRevokeTimestamp = redis.opsForValue().get(deviceRevokeKey);
                if (deviceRevokeTimestamp != null) {
                    long revokeMillis = Long.parseLong(deviceRevokeTimestamp);
                    Number issuedAtMillisClaim = claims.get("issuedAtMillis", Number.class);
                    long tokenIssuedAtMillis = issuedAtMillisClaim != null
                            ? issuedAtMillisClaim.longValue()
                            : claims.getIssuedAt().getTime();
                    if (tokenIssuedAtMillis <= revokeMillis) {
                        log.debug("Token predates device logout event — skipping authentication");
                        filterChain.doFilter(request, response);
                        return;
                    }
                }

                String email = claims.get("email", String.class);
                Boolean isPlatformAdmin = claims.get("isPlatformAdmin", Boolean.class);
                String tenantId = claims.get("tenantId", String.class);
                String role = claims.get("role", String.class);

                if (!Boolean.TRUE.equals(isPlatformAdmin) && isTenantSuspended(tenantId)
                        && !isBillingRecoveryPath(request)) {
                    log.debug("Rejecting request — tenant {} is suspended", tenantId);
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"success\":false,\"message\":\"Tenant account is suspended\",\"data\":null}");
                    return;
                }

                // Platform Admin bypasses the tenant's own IP whitelist — it's the tenant's
                // security control over ITS users, not something that should block support
                // access. Opt-in per tenant: a tenant with zero active whitelist entries is
                // unrestricted (see IpWhitelistGuard).
                if (!Boolean.TRUE.equals(isPlatformAdmin) && StringUtils.hasText(tenantId)) {
                    String clientIp = clientIpFrom(request);
                    if (!ipWhitelistGuard.isAllowed(UUID.fromString(tenantId), role, clientIp)) {
                        log.debug("Rejecting request — IP {} not whitelisted for tenant {}", clientIp, tenantId);
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write(
                                "{\"success\":false,\"message\":\"Access from this IP address is not allowed for this tenant\","
                                + "\"data\":null,\"errorCode\":\"IP_NOT_WHITELISTED\","
                                + "\"userMessage\":\"Truy cập bị từ chối do địa chỉ IP không nằm trong danh sách cho phép của công ty bạn.\"}");
                        return;
                    }
                }

                Set<String> permissions = Boolean.TRUE.equals(isPlatformAdmin)
                        ? loadPlatformAdminPermissions()
                        : loadUserPermissions(userId, tenantId);

                FamsUserDetails userDetails = new FamsUserDetails(
                        com.fams.modules.auth.entity.User.builder()
                                .id(userId)
                                .email(email)
                                .passwordHash("")
                                .displayName("")
                                .isPlatformAdmin(Boolean.TRUE.equals(isPlatformAdmin))
                                .build(),
                        permissions,
                        deviceId
                );

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                log.debug("Could not authenticate JWT token: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }
}
