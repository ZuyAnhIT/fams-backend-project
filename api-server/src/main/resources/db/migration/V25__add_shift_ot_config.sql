-- Add OT and check-in/out tolerance fields to shifts.
-- allow_overtime        — whether overtime is permitted for this shift
-- early_checkin_minutes — how many minutes before start_time a check-in is accepted
-- late_checkout_minutes — how many minutes after end_time a checkout is accepted

ALTER TABLE shifts
    ADD COLUMN allow_overtime        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN early_checkin_minutes INTEGER NOT NULL DEFAULT 0
                   CONSTRAINT shifts_early_checkin_min CHECK (early_checkin_minutes >= 0),
    ADD COLUMN late_checkout_minutes INTEGER NOT NULL DEFAULT 0
                   CONSTRAINT shifts_late_checkout_min CHECK (late_checkout_minutes >= 0);
