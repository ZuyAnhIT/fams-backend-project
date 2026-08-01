-- Tracks whether a photo was actually forwarded to the async AI verification job for this
-- response — the only reliable way to know a selfie exists in fams-ai's checkins/ storage
-- without calling out to fams-ai just to find out. Set at submit() time (see
-- CheckResponseService), never retroactively. Used by GET .../scheduled-checks/{checkId}/photo
-- (found missing via FE audit, 2026-08-01 — fams-ai already persists these selfies, but nothing
-- in the Java layer or fams-ai exposed a way to retrieve one back for HR review).
ALTER TABLE check_responses
    ADD COLUMN photo_submitted BOOLEAN NOT NULL DEFAULT false;
