-- An employee cannot physically be checked in at two places (or two assignments) at once.
-- The service layer already rejects a new check-in while another session is open
-- (CheckinService.submitCheckin), but that check-then-insert has a race window under
-- concurrent requests (e.g. two devices submitting near-simultaneously). This partial unique
-- index makes the invariant hold at the database level too: at most one row per employee with
-- check_out_at IS NULL and deleted_at IS NULL.
CREATE UNIQUE INDEX IF NOT EXISTS uq_checkins_one_open_session_per_employee
    ON checkins (employee_id)
    WHERE check_out_at IS NULL AND deleted_at IS NULL;
