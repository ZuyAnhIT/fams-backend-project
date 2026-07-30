-- 3-tier check-in/check-out verification policy (see docs/api/checkin-management-api.md):
--   gps_only          — GPS + geofence only (previous default behavior)
--   gps_face          — also requires a passed Face ID match (plain photo OR active-liveness challenge)
--   gps_face_liveness — also requires a PASSED active-liveness challenge specifically (photo alone insufficient)
-- Replaces the old Site.require_face_id_checkin boolean (which only ever meant the strictest
-- tier — no way to require Face ID without also requiring the full liveness sequence). A Shift
-- can override its Site's policy (e.g. a night shift at an otherwise gps_only site can still
-- require gps_face_liveness); NULL override means "inherit from Site".

ALTER TABLE sites ADD COLUMN checkin_policy VARCHAR(20) NOT NULL DEFAULT 'gps_only'
    CHECK (checkin_policy IN ('gps_only', 'gps_face', 'gps_face_liveness'));
UPDATE sites SET checkin_policy = CASE WHEN require_face_id_checkin THEN 'gps_face_liveness' ELSE 'gps_only' END;
ALTER TABLE sites DROP COLUMN require_face_id_checkin;

ALTER TABLE shifts ADD COLUMN checkin_policy_override VARCHAR(20)
    CHECK (checkin_policy_override IN ('gps_only', 'gps_face', 'gps_face_liveness'));

-- Check-out can now also carry its own Face ID/liveness verification result, separate from the
-- check-in ones already on this row (face_verified/liveness_verified/face_verify_score) — a
-- checkout that fails its OWN required verification must be distinguishable from a check-in
-- that failed, since they can genuinely differ (e.g. a mid-shift enrollment revoke).
ALTER TABLE checkins
    ADD COLUMN checkout_face_verified     BOOLEAN,
    ADD COLUMN checkout_liveness_verified BOOLEAN,
    ADD COLUMN checkout_face_verify_score DOUBLE PRECISION;

-- Liveness challenges can now also be started for purpose='checkout' (previously only
-- 'enroll'/'checkin' — checkout needs its own active-liveness proof, not a reused check-in one).
ALTER TABLE liveness_challenges DROP CONSTRAINT liveness_challenges_purpose_check;
ALTER TABLE liveness_challenges ADD CONSTRAINT liveness_challenges_purpose_check
    CHECK (purpose IN ('enroll', 'checkin', 'checkout'));
