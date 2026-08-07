-- FE feedback (2026-08-06): System Health needs job run duration to distinguish a healthy job
-- from one that is running but taking abnormally long. Nullable — historical rows and jobs that
-- haven't reported a timed run yet simply have no duration.
ALTER TABLE scheduled_job_status ADD COLUMN last_run_duration_ms BIGINT;

-- FE feedback (2026-08-06): "users:create" is currently overloaded to also mean "can see
-- unmasked employee PII" (email/phone) — too broad, violates least privilege. Add a dedicated
-- permission. Backfill: grant it to every role template that currently holds users:create, so
-- existing behavior does not regress the moment this ships — going forward, users:create and
-- PII-view are governed independently (MaskedSerializer/EmployeeExportService switch to check
-- this new permission instead).
INSERT INTO permissions (name, resource, action, description) VALUES
    ('employees:pii:read', 'employees', 'pii:read', 'View unmasked employee PII (email, phone) in API responses and exports')
ON CONFLICT (name) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, p.id
FROM role_permissions rp
JOIN permissions up ON up.id = rp.permission_id AND up.name = 'users:create'
JOIN permissions p ON p.name = 'employees:pii:read'
ON CONFLICT DO NOTHING;
