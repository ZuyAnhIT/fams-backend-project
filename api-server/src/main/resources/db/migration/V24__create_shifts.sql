-- Shift templates: define standard working hours per site.
-- OT configuration fields (allow_overtime, early_checkin_minutes, late_checkout_minutes)
-- are added in task 60 via V25.

CREATE TABLE shifts (
    id                  UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    site_id             UUID        NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    tenant_id           UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                VARCHAR(100) NOT NULL,
    start_time          TIME        NOT NULL,
    end_time            TIME        NOT NULL,
    allow_overnight     BOOLEAN     NOT NULL DEFAULT FALSE,
    status              VARCHAR(20) NOT NULL DEFAULT 'active'
                            CHECK (status IN ('active', 'inactive')),
    created_by          UUID        REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ
);

-- Shift name must be unique (case-insensitive) per site
CREATE UNIQUE INDEX uq_shifts_name_site
    ON shifts(site_id, lower(name))
    WHERE deleted_at IS NULL;

CREATE INDEX idx_shifts_site_id   ON shifts(site_id);
CREATE INDEX idx_shifts_tenant_id ON shifts(tenant_id);
CREATE INDEX idx_shifts_status    ON shifts(status) WHERE deleted_at IS NULL;

-- ── Seed shift permissions ────────────────────────────────────────────────────

INSERT INTO permissions (name, resource, action, description) VALUES
    ('shifts:create', 'shifts', 'create', 'Create a shift template for a site'),
    ('shifts:read',   'shifts', 'read',   'View shift templates'),
    ('shifts:update', 'shifts', 'update', 'Update a shift template'),
    ('shifts:delete', 'shifts', 'delete', 'Delete / deactivate a shift template'),
    ('shifts:list',   'shifts', 'list',   'List shift templates')
ON CONFLICT (name) DO NOTHING;

-- PLATFORM_ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'PLATFORM_ADMIN' AND r.tenant_id IS NULL
  AND p.name IN ('shifts:create','shifts:read','shifts:update','shifts:delete','shifts:list')
ON CONFLICT DO NOTHING;

-- TENANT_ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'TENANT_ADMIN' AND r.tenant_id IS NULL
  AND p.name IN ('shifts:create','shifts:read','shifts:update','shifts:delete','shifts:list')
ON CONFLICT DO NOTHING;

-- HR_MANAGER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'HR_MANAGER' AND r.tenant_id IS NULL
  AND p.name IN ('shifts:create','shifts:read','shifts:update','shifts:delete','shifts:list')
ON CONFLICT DO NOTHING;

-- SITE_SUPERVISOR — read and list only
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'SITE_SUPERVISOR' AND r.tenant_id IS NULL
  AND p.name IN ('shifts:read','shifts:list')
ON CONFLICT DO NOTHING;
