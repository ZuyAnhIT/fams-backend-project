package com.fams.modules.rbac.util;

import com.fams.modules.rbac.entity.UserRole;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Found via audit (2026-08-18): every login flow (password, refresh-token, Google, phone OTP)
 * picked the JWT's single {@code role} claim via {@code roles.get(0)}/{@code findFirst()} — the
 * order the DB query happened to return, not a business priority. A user holding two roles in the
 * same tenant (e.g. an employee freshly promoted to SITE_SUPERVISOR without their base EMPLOYEE
 * role being revoked — a realistic HR workflow, not just a test artifact) could silently land on
 * the wrong dashboard/permission set depending on row order. Centralizing the priority order here
 * so all four login paths resolve the same way.
 */
public final class PrimaryRoleResolver {

    private PrimaryRoleResolver() {}

    /** Index = priority, most-privileged first. Roles not listed here (e.g. narrower platform
     *  roles like PLATFORM_QA_REVIEWER) rank below every named tenant role but above nothing —
     *  they still beat an unrecognized/empty name via Integer.MAX_VALUE below. */
    private static final List<String> ORDER = List.of(
            "PLATFORM_ADMIN",
            "PLATFORM_SECURITY_AUDITOR",
            "PLATFORM_COMPLIANCE_OFFICER",
            "PLATFORM_BILLING_OPS",
            "PLATFORM_ONBOARDING_SPECIALIST",
            "PLATFORM_PARTNER_MANAGER",
            "PLATFORM_NOTIFICATION_MANAGER",
            "PLATFORM_QA_REVIEWER",
            "PLATFORM_SUPPORT_LEAD",
            "PLATFORM_STAFF",
            "TENANT_ADMIN",
            "HR_MANAGER",
            "SITE_SUPERVISOR",
            "EMPLOYEE"
    );

    private static int rank(UserRole userRole) {
        String name = userRole.getRole() != null ? userRole.getRole().getName() : null;
        int index = name != null ? ORDER.indexOf(name) : -1;
        return index < 0 ? ORDER.size() : index;
    }

    /** Picks the highest-priority role among the given tenant's active roles for this user.
     *  Returns null if the list is empty. */
    public static UserRole pickPrimaryForTenant(List<UserRole> roles, UUID tenantId) {
        return roles.stream()
                .filter(r -> Objects.equals(r.getTenantId(), tenantId))
                .min(Comparator.comparingInt(PrimaryRoleResolver::rank))
                .orElse(null);
    }

    /** Picks the primary tenant the same way callers always have (the first active-role row's
     *  tenant — session/tenant-switch semantics are handled separately, e.g.
     *  RefreshTokenService#activeTenantId), then the highest-priority role within that tenant. */
    public static UserRole pickPrimary(List<UserRole> roles) {
        if (roles.isEmpty()) return null;
        UUID primaryTenantId = roles.get(0).getTenantId();
        UserRole picked = pickPrimaryForTenant(roles, primaryTenantId);
        return picked != null ? picked : roles.get(0);
    }
}
