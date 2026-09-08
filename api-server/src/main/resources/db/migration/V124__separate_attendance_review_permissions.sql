-- Viewing operational attendance data and changing its legal/payroll outcome are different
-- responsibilities.  Previously checkins:list also allowed a status override and
-- attendance:list also allowed manual payroll-summary adjustment.  A custom/view-only role
-- therefore received write authority accidentally.

INSERT INTO permissions (name, resource, action, description)
SELECT v.name, v.resource, v.action, v.description
FROM (VALUES
    ('checkins:review',   'checkins',   'review', 'Accept or reject check-in evidence'),
    ('attendance:adjust', 'attendance', 'adjust', 'Manually adjust, unlock, or recompute attendance summaries')
) AS v(name, resource, action, description)
WHERE NOT EXISTS (SELECT 1 FROM permissions p WHERE p.name = v.name);

-- Tenant administrators and HR can perform both review and payroll correction.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('checkins:review', 'attendance:adjust')
WHERE r.name IN ('TENANT_ADMIN', 'HR_MANAGER')
  AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;

-- A site supervisor may review evidence only within their existing site scope.  They must not
-- manually rewrite the payroll summary.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name = 'checkins:review'
WHERE r.name = 'SITE_SUPERVISOR'
  AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;

-- Defensive repair for databases where the immutable system EMPLOYEE role was polluted by
-- manual seed/test data.  Employee self-service is exclusively checkins:create/checkins:read
-- and attendance:read; tenant-wide list/review/adjust permissions are never part of it.
DELETE FROM role_permissions rp
USING roles r, permissions p
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.name = 'EMPLOYEE'
  AND r.is_system = TRUE
  AND r.tenant_id IS NULL
  AND p.name IN ('checkins:list', 'checkins:review', 'attendance:list', 'attendance:adjust');
