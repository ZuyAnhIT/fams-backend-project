-- #104 (2026-08-18): random check's location_face_liveness mode previously accepted a single
-- passive photo for liveness scoring (AI-only anti-spoofing, MiniFASNet). Per explicit user
-- decision, upgraded to the same active-liveness challenge (head-pose/blink sequence) already
-- used by check-in/check-out at gps_face_liveness sites — adds 'random_check' as a valid purpose.
ALTER TABLE liveness_challenges DROP CONSTRAINT liveness_challenges_purpose_check;
ALTER TABLE liveness_challenges ADD CONSTRAINT liveness_challenges_purpose_check
    CHECK (purpose IN ('enroll', 'checkin', 'checkout', 'random_check'));
