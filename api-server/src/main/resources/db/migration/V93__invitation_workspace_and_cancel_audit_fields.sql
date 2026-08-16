-- #33/#34/#35 gap fixes (2026-08-15 manual QA):
-- - invite flow can now optionally pick a default workspace, applied to the WorkspaceMember
--   created when the invitation is accepted (AC required "chọn default role/workspace", only
--   role was implemented).
-- - cancel flow now records who cancelled and why (AC required "lưu cancelled_by/cancel_reason",
--   neither existed).
ALTER TABLE employee_invitations
    ADD COLUMN workspace_id UUID NULL,
    ADD COLUMN cancelled_by UUID NULL,
    ADD COLUMN cancel_reason VARCHAR(500) NULL,
    ADD COLUMN cancelled_at TIMESTAMPTZ NULL;
