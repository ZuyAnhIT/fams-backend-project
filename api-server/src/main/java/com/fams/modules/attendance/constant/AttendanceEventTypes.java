package com.fams.modules.attendance.constant;

public final class AttendanceEventTypes {

    private AttendanceEventTypes() {
    }

    /** #84 (2026-08-17): sent to the employee themselves when a past day's summary flips into
     *  missing_checkout=true (i.e. they never checked out). */
    public static final String MISSING_CHECKOUT_EMPLOYEE = "MISSING_CHECKOUT_EMPLOYEE";

    /** #84 (2026-08-17): sent to every tenant HR/admin holder of attendance:list when a past
     *  day's summary flips into missing_checkout=true, so HR can follow up/adjust. */
    public static final String MISSING_CHECKOUT_HR = "MISSING_CHECKOUT_HR";
}
