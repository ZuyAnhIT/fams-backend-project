package com.fams.shared.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class MaskingUtils {

    /** #145 (docs/api/backend-feature-audit-2026-08-07.md): redact-at-write-layer, same stance
     *  Stripe/GitHub take for audit logs — a field name landing in an oldValue/newValue map gets
     *  masked automatically, so a future call site doesn't have to remember to do it itself.
     *  totpSecret/backupCodes/nationalId aren't populated by any current audit call (verified:
     *  no service passes them today), but are listed defensively so the day someone dumps a
     *  User/Employee entity wholesale into an audit map, these can't leak by accident. */
    private static final Set<String> PII_KEYS = Set.of(
            "email", "phone", "deviceToken", "token", "password", "passwordHash",
            "refreshToken", "accessToken", "totpSecret", "backupCodes", "backupCode",
            "nationalId", "identityNumber", "idNumber"
    );

    private MaskingUtils() {}

    /** j***@gmail.com */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return email;
        int atIdx = email.indexOf('@');
        if (atIdx <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(atIdx);
    }

    /** ***567 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 3) return "***";
        return "***" + phone.substring(phone.length() - 3);
    }

    /** abcdef*** */
    public static String maskToken(String token) {
        if (token == null || token.length() <= 6) return "***";
        return token.substring(0, 6) + "***";
    }

    /**
     * Returns a copy of the map with known PII fields masked.
     * Non-string values for PII keys are replaced with "***".
     */
    public static Map<String, Object> maskAuditMap(Map<String, Object> map) {
        if (map == null) return null;
        Map<String, Object> masked = new HashMap<>(map);
        for (String key : PII_KEYS) {
            if (masked.containsKey(key)) {
                Object val = masked.get(key);
                if (val instanceof String s) {
                    masked.put(key, maskByKeyName(key, s));
                } else if (val != null) {
                    masked.put(key, "***");
                }
            }
        }
        return masked;
    }

    private static String maskByKeyName(String key, String value) {
        if (key.toLowerCase().contains("email")) return maskEmail(value);
        if (key.toLowerCase().contains("phone")) return maskPhone(value);
        return maskToken(value);
    }
}
