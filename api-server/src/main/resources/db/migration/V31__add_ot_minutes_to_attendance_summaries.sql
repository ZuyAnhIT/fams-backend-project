-- Task 83: add ot_minutes to attendance_summaries.
-- ot_minutes = total minutes worked beyond shift end time (capped by lateCheckoutMinutes per session).
-- Only non-zero when shift.allow_overtime = true and employee checked out after shift end.

ALTER TABLE attendance_summaries
    ADD COLUMN ot_minutes INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_att_summaries_ot ON attendance_summaries(ot_minutes) WHERE deleted_at IS NULL AND ot_minutes > 0;
