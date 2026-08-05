-- Grants SITE_SUPERVISOR the reports:list permission (audit 2026-08-04).
--
-- User story explicitly asks for site presence reporting to be available to
-- "HR/Admin/Supervisor", but SITE_SUPERVISOR held no reports:* permission at all — the
-- ReportController's 6 endpoints are all gated by a single reports:list check, so there was no
-- way to grant just the site-presence report without granting the whole module.
--
-- This is now safe to grant broadly: every ReportService method gated by reports:list was
-- audited in the same pass and now enforces site-scope for a restricted caller (via
-- SiteScopeService/resolveSiteFilterForReports) — a supervisor granted this permission sees
-- only their own site(s) on every report, the same guarantee already relied on for the Face ID
-- enrollment report. reports:export/attendance:export are NOT granted here — export was only
-- asked for HR/Admin in the user stories, this migration is deliberately narrower than that.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name = 'reports:list'
WHERE r.name = 'SITE_SUPERVISOR' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;
