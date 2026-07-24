package com.fams.shared.constants;

public final class AppConstants {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    // 1 hour — common middle ground among real systems (many use 15-30 min for a first
    // lock; a longer window here is offset by the account owner having an immediate
    // alternative: the lockout email sent below links to "forgot password", and a
    // successful reset clears the lock right away instead of waiting out the timer.
    public static final int LOCK_DURATION_MINUTES = 60;

    private AppConstants() {}
}
