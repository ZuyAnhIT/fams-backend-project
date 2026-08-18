package com.fams.modules.checkin.constant;

public final class CheckinEventTypes {

    private CheckinEventTypes() {
    }

    /** #113 (2026-08-18): sent to every tenant holder of checkins:list when an employee submits
     *  an explanation (note and/or evidence photo) for one of their own flagged check-ins —
     *  previously HR had no way to find out a new explanation existed short of proactively
     *  re-checking every pending_review/rejected record. */
    public static final String EXPLANATION_SUBMITTED_HR = "CHECKIN_EXPLANATION_SUBMITTED_HR";
}
