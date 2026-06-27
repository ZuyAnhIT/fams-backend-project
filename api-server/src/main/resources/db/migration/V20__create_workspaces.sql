-- Workspaces (departments / teams) for organising personnel within a tenant.

CREATE TABLE workspaces (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    type        VARCHAR(20) NOT NULL DEFAULT 'department'
                    CHECK (type IN ('department', 'team')),
    parent_id   UUID REFERENCES workspaces(id) ON DELETE SET NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'inactive')),
    created_by  UUID REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ
);

-- Workspace name must be unique per tenant (case-sensitive, ignoring soft-deleted rows)
CREATE UNIQUE INDEX uq_workspaces_name_tenant
    ON workspaces(tenant_id, lower(name))
    WHERE deleted_at IS NULL;

CREATE INDEX idx_workspaces_tenant_id ON workspaces(tenant_id);
CREATE INDEX idx_workspaces_parent_id ON workspaces(parent_id);
CREATE INDEX idx_workspaces_status    ON workspaces(status);

-- ── Workspace permissions ───────────────────────────────────────────────────

INSERT INTO permissions (name, resource, action, description)
SELECT v.name, v.resource, v.action, v.description
FROM (VALUES
    ('workspaces:create', 'workspaces', 'create', 'Create workspaces'),
    ('workspaces:read',   'workspaces', 'read',   'View a workspace'),
    ('workspaces:update', 'workspaces', 'update', 'Update workspaces'),
    ('workspaces:delete', 'workspaces', 'delete', 'Delete workspaces'),
    ('workspaces:list',   'workspaces', 'list',   'List workspaces')
) AS v(name, resource, action, description)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = v.name);

-- Grant workspace permissions to PLATFORM_ADMIN (all permissions)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'PLATFORM_ADMIN' AND r.tenant_id IS NULL
  AND p.resource = 'workspaces'
ON CONFLICT DO NOTHING;

-- Grant workspace permissions to TENANT_ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN (
    'workspaces:create', 'workspaces:read', 'workspaces:update',
    'workspaces:delete', 'workspaces:list'
)
WHERE r.name = 'TENANT_ADMIN' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;

-- Grant read/list to HR_MANAGER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('workspaces:read', 'workspaces:list')
WHERE r.name = 'HR_MANAGER' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;
