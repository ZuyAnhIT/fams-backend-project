package com.fams.modules.violation.constant;

public final class ViolationEventTypes {

    private ViolationEventTypes() {
    }

    /** #106/#107 (2026-08-18): sent to the employee themselves when a random-check violation
     *  (no_response, location_fail, face_fail, liveness_fail) is created against them. */
    public static final String RANDOM_CHECK_VIOLATION_EMPLOYEE = "RANDOM_CHECK_VIOLATION_EMPLOYEE";

    /** #106/#107 (2026-08-18): sent to every tenant holder of violations:list when a random-check
     *  violation is created, so HR can follow up. */
    public static final String RANDOM_CHECK_VIOLATION_HR = "RANDOM_CHECK_VIOLATION_HR";
}
