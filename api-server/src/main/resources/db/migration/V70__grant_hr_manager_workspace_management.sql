-- HR_MANAGER was seeded (V20) with only workspaces:read/list — meaning HR could view the org
-- structure but not create or edit departments/teams, contradicting the actual business requirement
-- that "HR/admin hoặc quản lý nhân sự" manage the org chart. Real HRIS systems (BambooHR, Deputy)
-- let HR create/edit departments without escalating to a company-wide admin role.
-- Deletion is intentionally left TENANT_ADMIN-only (matches the higher bar already used for
-- deleting roles): removing a workspace is harder to reverse than creating/renaming one.

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('workspaces:create', 'workspaces:update')
WHERE r.name = 'HR_MANAGER' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;
