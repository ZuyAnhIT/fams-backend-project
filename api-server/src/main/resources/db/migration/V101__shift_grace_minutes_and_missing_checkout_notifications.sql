-- #81: grace period before a late check-in is actually flagged as late. Default 5 minutes per
-- decision from the project owner (2026-08-17) — existing shifts get the default retroactively
-- (their past attendance figures are untouched, since late/early/OT are always computed from a
-- per-checkin snapshot captured at check-in time, never re-derived from the live Shift row).
ALTER TABLE shifts
    ADD COLUMN grace_minutes INTEGER NOT NULL DEFAULT 5;

ALTER TABLE shifts
    ADD CONSTRAINT chk_shifts_grace_minutes CHECK (grace_minutes >= 0);

-- Snapshot column on checkins, same pattern as shift_late_checkout_minutes etc. — captured at
-- check-in time so a later Shift edit can never retroactively change an already-computed day's
-- late figure.
ALTER TABLE checkins
    ADD COLUMN shift_grace_minutes INTEGER;
