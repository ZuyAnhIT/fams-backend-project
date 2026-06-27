-- Workspace membership: links employees to workspaces with an optional workspace-level role.

CREATE TABLE workspace_members (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    employee_id  UUID NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    role         VARCHAR(50) NOT NULL DEFAULT 'member'
                     CHECK (role IN ('member', 'lead', 'manager')),
    assigned_by  UUID REFERENCES users(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at   TIMESTAMPTZ
);

-- One active membership per employee per workspace
CREATE UNIQUE INDEX uq_workspace_members_emp_ws
    ON workspace_members(workspace_id, employee_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_workspace_members_workspace_id ON workspace_members(workspace_id);
CREATE INDEX idx_workspace_members_employee_id  ON workspace_members(employee_id);
CREATE INDEX idx_workspace_members_tenant_id    ON workspace_members(tenant_id);

-- ── Workspace member permissions ────────────────────────────────────────────

INSERT INTO permissions (name, resource, action, description)
SELECT v.name, v.resource, v.action, v.description
FROM (VALUES
    ('workspace_members:create', 'workspace_members', 'create', 'Assign employee to workspace'),
    ('workspace_members:read',   'workspace_members', 'read',   'View workspace member'),
    ('workspace_members:delete', 'workspace_members', 'delete', 'Remove employee from workspace'),
    ('workspace_members:list',   'workspace_members', 'list',   'List workspace members')
) AS v(name, resource, action, description)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = v.name);

-- PLATFORM_ADMIN gets all new permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'PLATFORM_ADMIN' AND r.tenant_id IS NULL
  AND p.resource = 'workspace_members'
ON CONFLICT DO NOTHING;

-- TENANT_ADMIN gets all workspace_members permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN (
    'workspace_members:create', 'workspace_members:read',
    'workspace_members:delete', 'workspace_members:list'
)
WHERE r.name = 'TENANT_ADMIN' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;

-- HR_MANAGER gets all workspace_members permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN (
    'workspace_members:create', 'workspace_members:read',
    'workspace_members:delete', 'workspace_members:list'
)
WHERE r.name = 'HR_MANAGER' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;
