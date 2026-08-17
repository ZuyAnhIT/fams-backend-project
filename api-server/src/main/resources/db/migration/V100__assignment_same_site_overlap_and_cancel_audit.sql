-- #63: allow multiple non-overlapping active assignments per employee+site — the old
-- unconditional "one active assignment per employee+site" constraint blocked legitimate
-- future scheduling (e.g. pre-creating next month's assignment while this month's is still
-- active). Same-site overlap is now enforced at the service layer with the same date/hour-aware
-- conflict check already used for cross-site conflicts (see AssignmentService).
DROP INDEX IF EXISTS uq_assignments_employee_site_active;

-- #66: track who/when cancelled an assignment — same pattern already applied to
-- employee_invitations (cancelled_by/cancelled_at, migration V93).
ALTER TABLE assignments
    ADD COLUMN cancelled_by UUID REFERENCES users(id),
    ADD COLUMN cancelled_at TIMESTAMPTZ;
