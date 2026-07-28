-- Fixes per Web/App team review of the active-liveness rollout (see
-- docs/api/face-id-management-api.md mục 0.2):
--   1. A 'checkin' challenge must be bound to the site it was started for, so a challenge
--      completed at Site A can't be carried over and consumed at Site B.
--   2. HR needs to actually SEE the pending submission's reference photo before approving —
--      previously the review queue was metadata-only ("duyệt mù" / blind approval).

ALTER TABLE liveness_challenges
    ADD COLUMN site_id UUID REFERENCES sites(id);

ALTER TABLE face_profiles
    ADD COLUMN pending_photo_path TEXT;
