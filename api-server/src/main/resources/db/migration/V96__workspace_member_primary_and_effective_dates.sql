-- #46/#47 gap fixes (2026-08-16 manual QA):
-- - is_primary: AC for #46 requires "chỉ một primary workspace active" per employee — this
--   concept didn't exist in the schema at all before.
-- - effective_from: when the membership actually starts (HR may back/future-date it), distinct
--   from created_at (when the row was inserted).
-- - left_at: when a membership ended (transfer or explicit removal) — distinct from the generic
--   deleted_at soft-delete column so "left because transferred/removed" is recorded explicitly,
--   matching the #47 AC's "left_at/effective_to" requirement.
ALTER TABLE workspace_members
    ADD COLUMN is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN effective_from DATE NULL,
    ADD COLUMN left_at TIMESTAMPTZ NULL;

-- Backfill effective_from for existing rows so historical memberships aren't left null.
UPDATE workspace_members SET effective_from = created_at::date WHERE effective_from IS NULL;

-- Partial unique index: enforces at most one active primary membership per (tenant, employee) at
-- the DB level, not just in application code — belt-and-suspenders against race conditions.
CREATE UNIQUE INDEX idx_workspace_members_one_primary_per_employee
    ON workspace_members (tenant_id, employee_id)
    WHERE is_primary = TRUE AND deleted_at IS NULL;
