-- Issue #14 (docs/issues/ISSUES.md): allow an assignment to recur on only specific days of the
-- week (e.g. "Mon/Wed/Fri only") instead of every single day in its start/end date range.
--
-- Bitmask: bit N (0-indexed) set means the assignment is active on ISO day-of-week N+1
-- (bit 0 = Monday ... bit 6 = Sunday), so values range 1-127. NULL means "every day", which is
-- exactly the current behavior — fully backward compatible with all existing assignments and
-- with any INSERT that omits the column.
ALTER TABLE assignments ADD COLUMN days_of_week SMALLINT;

ALTER TABLE assignments ADD CONSTRAINT chk_assignments_days_of_week
    CHECK (days_of_week IS NULL OR (days_of_week BETWEEN 1 AND 127));

COMMENT ON COLUMN assignments.days_of_week IS
    'Bitmask of active ISO weekdays (bit0=Monday..bit6=Sunday, 1-127). NULL = every day (default, backward compatible).';
