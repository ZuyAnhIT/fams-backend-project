-- Face ID hardening (see docs/api/face-id-management-api.md):
--   1. Every enrollment/re-enrollment now goes through an HR review queue instead of
--      auto-activating — a submitted batch lands in `pending_embedding`, and only becomes the
--      live `embedding` once approved. This lets a re-enrollment request sit in review without
--      breaking the employee's existing ability to check in with their currently-approved face.
--   2. Sites can require Face ID for check-in (`require_face_id_checkin`).
--   3. Violations can now originate from a regular check-in's face verification failing, not
--      just from the random-check module — needs a nullable FK back to `checkins`.

ALTER TABLE face_profiles
    ADD COLUMN review_status      VARCHAR(20) NOT NULL DEFAULT 'none'
                                       CHECK (review_status IN ('none', 'pending', 'rejected')),
    ADD COLUMN pending_photo_count INT,
    ADD COLUMN submitted_at        TIMESTAMPTZ,
    ADD COLUMN reviewed_by         UUID,
    ADD COLUMN reviewed_at         TIMESTAMPTZ,
    ADD COLUMN rejection_reason    TEXT;
    -- pending_embedding (DOUBLE PRECISION[]) is added below, intentionally left unmapped in the
    -- Java entity — same convention as `embedding`: only fams-ai touches raw biometric vectors.

ALTER TABLE face_profiles ADD COLUMN pending_embedding DOUBLE PRECISION[];

-- 'pending' used to be a `status` value (never actually reached by the old code path — enroll
-- went straight to 'enrolled'). That meaning now lives in `review_status` instead, so drop it
-- from the status domain. Defensive backfill in case any row somehow has it.
UPDATE face_profiles SET status = 'not_enrolled' WHERE status = 'pending';
ALTER TABLE face_profiles DROP CONSTRAINT face_profiles_status_check;
ALTER TABLE face_profiles ADD CONSTRAINT face_profiles_status_check
    CHECK (status IN ('not_enrolled', 'enrolled', 'revoked'));

ALTER TABLE sites
    ADD COLUMN require_face_id_checkin BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE violations
    ADD COLUMN checkin_id UUID REFERENCES checkins(id);

CREATE INDEX idx_violations_checkin ON violations (checkin_id) WHERE checkin_id IS NOT NULL;
