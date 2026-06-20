package com.fams.shared.security;

import com.fams.modules.auth.service.LogoutService;
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
import java.util.Collections;
import java.util.UUID;

@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final StringRedisTemplate redis;

    public JwtAuthFilter(JwtProvider jwtProvider, StringRedisTemplate redis) {
        this.jwtProvider = jwtProvider;
        this.redis = redis;
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

                String userRevokeKey = com.fams.modules.auth.service.LogoutService.USER_REVOKE_PREFIX + userId;
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

                FamsUserDetails userDetails = new FamsUserDetails(
                        com.fams.modules.auth.entity.User.builder()
                                .id(userId)
                                .email(email)
                                .passwordHash("")
                                .displayName("")
                                .build()
                );

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                Collections.emptyList()
                        );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                log.debug("Could not authenticate JWT token: {}", e.getMessage());
                // Let Spring Security handle the 401 — do not throw
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
