-- #60 (docs/api/backend-feature-audit-2026-08-07.md): shifts.allow_overtime (V25) only ever
-- toggled OT on/off with no cap on how much — HR could not set "OT allowed, but flag it if it
-- goes past N hours/day or M hours/week". Warn-only by product decision (2026-08-07): FAMS
-- never blocks a checkout, it only surfaces an exceeded-limit flag on the day's attendance
-- summary for HR/payroll review, same non-blocking stance used for hasRandomCheckFailure (V80)
-- and hasPendingReviewSession (V79) — mirrors how Deputy/ADP handle manager-approved OT caps.
--
-- max_ot_minutes_per_day/week — NULL means unlimited (same "NULL = unlimited" convention as
-- plan_limits, see subscription/entity/PlanLimits.java). Minutes, not hours, for the same
-- granularity as the existing early_checkin_minutes/late_checkout_minutes fields (V25).

ALTER TABLE shifts
    ADD COLUMN max_ot_minutes_per_day  INTEGER
                   CONSTRAINT shifts_max_ot_per_day_min CHECK (max_ot_minutes_per_day IS NULL OR max_ot_minutes_per_day >= 0),
    ADD COLUMN max_ot_minutes_per_week INTEGER
                   CONSTRAINT shifts_max_ot_per_week_min CHECK (max_ot_minutes_per_week IS NULL OR max_ot_minutes_per_week >= 0);

-- Snapshot of the two new limits, same pattern as shift_allow_overtime/shift_late_checkout_minutes
-- (V72) — a shift's limit edited after check-in must never retroactively change an already-
-- computed day's otDailyLimitExceeded/otWeeklyLimitExceeded flags.
ALTER TABLE checkins
    ADD COLUMN shift_max_ot_minutes_per_day  INTEGER,
    ADD COLUMN shift_max_ot_minutes_per_week INTEGER;

-- Informational flags only, same style as has_random_check_failure (V80) — never mutate
-- total_work_minutes/ot_minutes, HR reviews and decides via /adjust.
ALTER TABLE attendance_summaries
    ADD COLUMN ot_daily_limit_exceeded  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN ot_weekly_limit_exceeded BOOLEAN NOT NULL DEFAULT FALSE;
