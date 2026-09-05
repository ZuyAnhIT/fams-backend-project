-- Random checks are normally spread over each shift's own working interval. A custom clock
-- window is opt-in and is intersected strictly with the shift interval.
ALTER TABLE random_check_configs
    ADD COLUMN window_mode VARCHAR(20) NOT NULL DEFAULT 'full_shift',
    ADD COLUMN manual_checks_allowed BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE random_check_configs
    ADD CONSTRAINT chk_random_check_window_mode
        CHECK (window_mode IN ('full_shift', 'custom_window'));

COMMENT ON COLUMN random_check_configs.window_mode IS
    'full_shift = use each shift interval; custom_window = intersect allowed times with shift';
COMMENT ON COLUMN random_check_configs.manual_checks_allowed IS
    'Independent switch for immediate HR-triggered checks; is_active controls automatic checks';

-- Per-shift switches avoid cloning a full site/company policy merely to exclude one shift.
ALTER TABLE shifts
    ADD COLUMN random_check_policy VARCHAR(20) NOT NULL DEFAULT 'inherit',
    ADD COLUMN manual_check_policy VARCHAR(20) NOT NULL DEFAULT 'inherit';

ALTER TABLE shifts
    ADD CONSTRAINT chk_shift_random_check_policy
        CHECK (random_check_policy IN ('inherit', 'enabled', 'disabled')),
    ADD CONSTRAINT chk_shift_manual_check_policy
        CHECK (manual_check_policy IN ('inherit', 'enabled', 'disabled'));

COMMENT ON COLUMN shifts.random_check_policy IS
    'inherit/enabled/disabled override for automatically scheduled random checks';
COMMENT ON COLUMN shifts.manual_check_policy IS
    'inherit/enabled/disabled override for immediate HR-triggered random checks';
