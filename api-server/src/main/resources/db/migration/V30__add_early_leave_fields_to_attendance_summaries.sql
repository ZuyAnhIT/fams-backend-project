-- Task 82: add is_early_leave and early_leave_minutes to attendance_summaries.
-- is_early_leave = last check-out was before the shift end time.
-- early_leave_minutes = minutes between last check-out and shift end (0 when not early or no shift/checkout).

ALTER TABLE attendance_summaries
    ADD COLUMN is_early_leave      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN early_leave_minutes INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_att_summaries_is_early_leave ON attendance_summaries(is_early_leave) WHERE deleted_at IS NULL;
