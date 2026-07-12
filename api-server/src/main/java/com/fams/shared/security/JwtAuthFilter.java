package com.fams.shared.security;

import com.fams.modules.auth.service.LogoutService;
import com.fams.modules.rbac.repository.PermissionRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
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

    public static final String PERMS_CACHE_PREFIX = "auth:perms:";
    static final String PLATFORM_ADMIN_PERMS_KEY = PERMS_CACHE_PREFIX + "platform_admin";
    private static final long PERMS_CACHE_TTL_SECONDS = 300; // 5 min

    private final JwtProvider jwtProvider;
    private final StringRedisTemplate redis;
    private final UserRoleRepository userRoleRepository;
    private final PermissionRepository permissionRepository;

    public JwtAuthFilter(JwtProvider jwtProvider,
                         StringRedisTemplate redis,
                         UserRoleRepository userRoleRepository,
                         PermissionRepository permissionRepository) {
        this.jwtProvider = jwtProvider;
        this.redis = redis;
        this.userRoleRepository = userRoleRepository;
        this.permissionRepository = permissionRepository;
    }

    private boolean isTenantSuspended(String tenantId) {
        if (tenantId == null || tenantId.isEmpty()) return false;
        return Boolean.TRUE.equals(redis.hasKey(TenantService.TENANT_SUSPENDED_PREFIX + tenantId));
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
        if (tenantId == null || tenantId.isEmpty()) return Set.of();
        String cacheKey = PERMS_CACHE_PREFIX + userId + ":" + tenantId;
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            if (cached.isEmpty()) return Set.of();
            return new HashSet<>(Arrays.asList(cached.split(",")));
        }
        Set<String> perms = userRoleRepository.findPermissionNamesByUserIdAndTenantId(
                userId, UUID.fromString(tenantId));
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

                String userRevokeKey = LogoutService.USER_REVOKE_PREFIX + userId;
                String revokeTimestampStr = redis.opsForValue().get(userRevokeKey);
                if (revokeTimestampStr != null) {
                    long revokeSeconds = Long.parseLong(revokeTimestampStr);
                    long tokenIssuedAtSeconds = claims.getIssuedAt().getTime() / 1000;
                    if (tokenIssuedAtSeconds <= revokeSeconds) {
                        log.debug("Token predates logout-all event — skipping authentication");
                        filterChain.doFilter(request, response);
                        return;
                    }
                }

                String email = claims.get("email", String.class);
                Boolean isPlatformAdmin = claims.get("isPlatformAdmin", Boolean.class);
                String tenantId = claims.get("tenantId", String.class);

                if (!Boolean.TRUE.equals(isPlatformAdmin) && isTenantSuspended(tenantId)) {
                    log.debug("Rejecting request — tenant {} is suspended", tenantId);
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"success\":false,\"message\":\"Tenant account is suspended\",\"data\":null}");
                    return;
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
                        permissions
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
