-- Task 81: add is_late and late_minutes to attendance_summaries.
-- is_late = first check-in was after the shift start time.
-- late_minutes = minutes between shift start and first check-in (0 when not late or no shift).

ALTER TABLE attendance_summaries
    ADD COLUMN is_late      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN late_minutes INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_att_summaries_is_late ON attendance_summaries(is_late) WHERE deleted_at IS NULL;
