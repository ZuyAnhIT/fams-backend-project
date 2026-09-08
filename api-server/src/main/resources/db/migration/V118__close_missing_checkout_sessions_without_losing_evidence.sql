-- A missing checkout must remain visible as missing attendance evidence, but it must not leave
-- the employee "currently checked in" forever. session_closed_at is the logical end of the
-- session; check_out_at remains NULL when no real checkout evidence exists.
ALTER TABLE checkins
    ADD COLUMN IF NOT EXISTS session_closed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS session_close_reason VARCHAR(30);

ALTER TABLE checkins
    DROP CONSTRAINT IF EXISTS checkins_session_close_reason_check;

ALTER TABLE checkins
    ADD CONSTRAINT checkins_session_close_reason_check
        CHECK (session_close_reason IS NULL
            OR session_close_reason IN ('checkout', 'missing_checkout', 'admin_closed'));

DROP INDEX IF EXISTS uq_checkins_one_open_session_per_employee;
DROP INDEX IF EXISTS uq_checkins_open_session;

-- Only a session with neither checkout evidence nor a logical closure is still open.
CREATE UNIQUE INDEX uq_checkins_one_open_session_per_employee
    ON checkins (employee_id)
    WHERE check_out_at IS NULL AND session_closed_at IS NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX uq_checkins_open_session
    ON checkins (assignment_id)
    WHERE check_out_at IS NULL AND session_closed_at IS NULL AND deleted_at IS NULL;
