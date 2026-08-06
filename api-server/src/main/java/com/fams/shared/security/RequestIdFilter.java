package com.fams.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns every incoming request a request ID — reused from the client's own {@code X-Request-Id}
 * header if it sent one, otherwise generated fresh — and stores it as a request attribute so
 * {@link HttpRequestUtils#currentRequestId()} can read it from anywhere downstream (services,
 * not just controllers) without threading an HttpServletRequest through every method signature,
 * same pattern already used for {@link HttpRequestUtils#currentIpAddress()}.
 *
 * <p>Added 2026-08-05: every {@code AuditLogService.record(...)} call site in the codebase was
 * passing {@code requestId=null} — the column existed and the story asked for it, but nothing
 * ever actually populated it, so there was no way to correlate multiple audit rows written by
 * the same HTTP request. Runs at the very front of the filter chain (highest precedence) so the
 * ID is available to every filter/service that runs after it, including JwtAuthFilter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "fams.requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (!StringUtils.hasText(requestId)) {
            requestId = UUID.randomUUID().toString();
        }
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        filterChain.doFilter(request, response);
    }
}
