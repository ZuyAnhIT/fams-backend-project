package com.fams.modules.violation.util;

/**
 * Severity derived from {@code Violation.violationType} for reporting/analytics purposes
 * (audit 2026-08-04 — the violation report story asked for a severity breakdown, but nothing
 * in the violation workflow itself sets or reads a severity value, so there is no column to
 * read one from). HIGH covers face/liveness failures because they bear directly on identity
 * verification — the strongest signal of possible buddy-punching or spoofing. MEDIUM covers a
 * confirmed-wrong location, a real but usually more innocuous discrepancy (GPS drift, being at
 * an approved off-site location). LOW covers no_response, the weakest signal of wrongdoing —
 * a dead phone or missed notification explains most of these.
 *
 * <p>This is a fixed, computed classification, not a per-violation editable field — if HR needs
 * to override severity on a case-by-case basis in the future, that would need a real column
 * and a dedicated update endpoint, which no current story asks for.
 */
public enum ViolationSeverity {
    HIGH,
    MEDIUM,
    LOW;

    public static ViolationSeverity of(String violationType) {
        if (violationType == null) {
            return LOW;
        }
        return switch (violationType) {
            case "face_fail", "liveness_fail" -> HIGH;
            case "location_fail" -> MEDIUM;
            // face_verify_timeout (2026-08-12): the AI callback never arrived — this is likely an
            // infra hiccup, not a confirmed identity mismatch, so it gets the same low-confidence
            // treatment as no_response rather than being lumped in with a real face/liveness fail.
            case "no_response", "face_verify_timeout" -> LOW;
            default -> LOW;
        };
    }
}
