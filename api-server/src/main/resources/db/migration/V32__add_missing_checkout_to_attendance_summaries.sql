-- Task 84: add missing_checkout to attendance_summaries.
-- missing_checkout = true when a past day has at least one check-in session with no check-out.
-- Set to true by the nightly catch-up job; false while the session is still happening today.

ALTER TABLE attendance_summaries
    ADD COLUMN missing_checkout BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_att_summaries_missing_checkout
    ON attendance_summaries(missing_checkout) WHERE deleted_at IS NULL AND missing_checkout = TRUE;
