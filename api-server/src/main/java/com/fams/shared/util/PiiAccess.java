package com.fams.shared.util;

import com.fams.shared.security.FamsUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Single source of truth for "can the current caller see unmasked employee PII (email/phone)" —
 * extracted 2026-08-06 (FE feedback: response should carry an explicit {@code piiMasked} flag
 * instead of FE having to guess from a "***" pattern) so {@link MaskedSerializer} (drives actual
 * JSON masking) and any DTO-building code that needs to report {@code piiMasked} as metadata
 * use the EXACT same check — duplicating this logic in two places would risk them drifting out
 * of sync, which would make the metadata actively misleading (worse than not having it).
 *
 * Gate is {@code employees:pii:read} (dedicated permission since migration V86, replacing the
 * previously overloaded {@code users:create}) or {@code PLATFORM_ADMIN}.
 */
public final class PiiAccess {

    private static final String PII_READ_PERMISSION = "employees:pii:read";

    private PiiAccess() {}

    public static boolean currentCallerCanViewUnmaskedPii() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof FamsUserDetails user)) return false;
        if (user.isPlatformAdmin()) return true;
        return user.getAuthorities().stream()
                .anyMatch(a -> PII_READ_PERMISSION.equals(a.getAuthority()));
    }
}
