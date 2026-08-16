-- #48/#51 gap fixes (2026-08-16 manual QA):
-- - consent_version/consent_ip/consent_device: AC for #48 requires recording WHICH version of the
--   consent text the employee agreed to, and from where/what device — previously only a bare
--   boolean + timestamp were stored, with no way to tell if a later policy update invalidated an
--   old consent, or to prove consent provenance for a legal dispute (Nghị định 13/2023/NĐ-CP).
-- - deleted_reason/deleted_by: AC for #51 requires recording why a Face ID profile was revoked and
--   by whom — previously revoke only flipped status/revoked_at (via fams-ai), with zero way to
--   answer "who revoked this and why" after the fact.
ALTER TABLE face_profiles
    ADD COLUMN consent_version VARCHAR(20) NULL,
    ADD COLUMN consent_ip VARCHAR(64) NULL,
    ADD COLUMN consent_device VARCHAR(255) NULL,
    ADD COLUMN deleted_reason VARCHAR(500) NULL,
    ADD COLUMN deleted_by UUID NULL;
