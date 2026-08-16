-- #39/#40 gap fixes (2026-08-16 manual QA):
-- - terminated_at records WHEN an employee's status last became "terminated" (status alone
--   can't answer "since when has this person been gone" for payroll cutoffs/reports).
-- - national_id (CCCD/CMND), stored plain and masked in API responses using the same
--   employees:pii:read convention already used for email/phone — not a new at-rest encryption
--   mechanism, kept consistent with the rest of this codebase.
ALTER TABLE employees
    ADD COLUMN terminated_at TIMESTAMPTZ NULL,
    ADD COLUMN national_id VARCHAR(50) NULL;
