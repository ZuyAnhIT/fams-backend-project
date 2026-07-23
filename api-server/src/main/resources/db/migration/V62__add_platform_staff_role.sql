-- Issue #10 (docs/issues/ISSUES.md): new "Platform Staff" role below Platform Admin — narrower,
-- operational-support scope (view/list tenants, view audit logs, view system status), no
-- create/update/delete/sensitive-config rights.
--
-- user_roles.tenant_id was NOT NULL (every assignment required a tenant context) — there was no
-- way to represent a platform-wide (cross-tenant) role assignment at all. Relaxing it to nullable,
-- mirroring roles.tenant_id which is already nullable for exactly this reason (system vs
-- tenant-owned roles).
ALTER TABLE user_roles ALTER COLUMN tenant_id DROP NOT NULL;

-- Postgres treats every NULL as distinct in a plain UNIQUE constraint, so without this a user
-- could be assigned the same platform-scoped role many times over with no constraint violation.
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_roles_platform
    ON user_roles (user_id, role_id)
    WHERE tenant_id IS NULL AND deleted_at IS NULL;

-- New permission for the system-status/operational-monitoring endpoint, which previously had no
-- permission-string check at all (hasRole('PLATFORM_ADMIN') only).
INSERT INTO permissions (name, resource, action, description)
SELECT 'system:read', 'system', 'read', 'View platform operational status (health, jobs, queue depth)'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'system:read');

INSERT INTO roles (name, description, is_system)
SELECT 'PLATFORM_STAFF', 'Platform support staff — read-only operational access below Platform Admin', TRUE
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'PLATFORM_STAFF' AND tenant_id IS NULL);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('tenants:read', 'tenants:list', 'audit:read', 'audit:list', 'system:read')
WHERE r.name = 'PLATFORM_STAFF' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;
