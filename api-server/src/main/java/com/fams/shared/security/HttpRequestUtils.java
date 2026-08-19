package com.fams.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Issue #6 (docs/issues/ISSUES.md): captures User-Agent/IP at login time so a "list my
 * sessions" screen can show something recognizable ("Chrome on Windows", "192.168.1.5")
 * instead of just an opaque deviceId. Reads from the current request via
 * {@link RequestContextHolder} so login/register/refresh services don't each need an
 * HttpServletRequest parameter threaded through their public method signatures.
 */
public final class HttpRequestUtils {

    private static final int MAX_LENGTH = 500;

    private HttpRequestUtils() {
    }

    public static String currentUserAgent() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String ua = request.getHeader("User-Agent");
        return truncate(ua);
    }

    /** Reads the request ID {@link RequestIdFilter} attached to the current request (client-sent
     *  {@code X-Request-Id} if present, otherwise a fresh generated one) — see that filter's
     *  javadoc. Null outside a request context (e.g. a scheduled job), same as the other
     *  current*() methods here. */
    public static String currentRequestId() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value != null ? truncate(value.toString()) : null;
    }

    public static String currentIpAddress() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        // X-Forwarded-For is set by reverse proxies (nginx, load balancers) — the first
        // entry is the original client IP. Falls back to the direct connection's IP
        // when there's no proxy in front of the app (e.g. plain local dev).
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return truncate(forwardedFor.split(",")[0].trim());
        }
        return truncate(request.getRemoteAddr());
    }

    /** #138 (2026-08-19 follow-up): request path, used by AuditLogService#record to auto-populate
     *  AuditLog.endpoint without touching any of its ~50 call sites — same "current*() reads from
     *  RequestContextHolder" pattern as every other method here. Null outside a request context
     *  (e.g. a scheduled job), same as the others. */
    public static String currentRequestPath() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : truncate(request.getRequestURI());
    }

    /** #138 (2026-08-19 follow-up): marks the current request as having written at least one
     *  audit row, so RequestIdFilter's post-response httpStatus backfill can skip the (indexed
     *  but still non-free) UPDATE for the vast majority of requests — plain GETs and any other
     *  call that never reaches AuditLogService#record — instead of firing on every request. */
    public static void markAuditWritten() {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            request.setAttribute(AUDIT_WRITTEN_ATTRIBUTE, Boolean.TRUE);
        }
    }

    public static boolean wasAuditWritten(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(AUDIT_WRITTEN_ATTRIBUTE));
    }

    private static final String AUDIT_WRITTEN_ATTRIBUTE = "fams.auditWritten";

    private static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest();
        }
        return null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
    }
}
