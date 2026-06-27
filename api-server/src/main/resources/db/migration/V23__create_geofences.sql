-- Geofences: polygon boundaries for site check-in validation.
-- Each site may have at most one active geofence at a time.
-- Previous geofences are retained with status 'superseded' for history (task 58).
-- Coordinates stored as a JSON array of [longitude, latitude] pairs (GeoJSON order).

CREATE TABLE geofences (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    site_id         UUID        NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    coordinates     TEXT        NOT NULL,
    buffer_meters   INTEGER     NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active', 'superseded')),
    created_by      UUID        REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ
);

-- Only one active geofence per site at a time
CREATE UNIQUE INDEX uq_geofences_site_active
    ON geofences(site_id)
    WHERE status = 'active' AND deleted_at IS NULL;

CREATE INDEX idx_geofences_site_id   ON geofences(site_id);
CREATE INDEX idx_geofences_tenant_id ON geofences(tenant_id);

-- ── Seed geofence permissions ─────────────────────────────────────────────────

INSERT INTO permissions (name, resource, action, description) VALUES
    ('geofences:create', 'geofences', 'create', 'Create a geofence for a site'),
    ('geofences:read',   'geofences', 'read',   'View geofence data'),
    ('geofences:update', 'geofences', 'update', 'Replace the active geofence'),
    ('geofences:delete', 'geofences', 'delete', 'Delete / deactivate a geofence')
ON CONFLICT (name) DO NOTHING;

-- PLATFORM_ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'PLATFORM_ADMIN' AND r.tenant_id IS NULL
  AND p.name IN ('geofences:create','geofences:read','geofences:update','geofences:delete')
ON CONFLICT DO NOTHING;

-- TENANT_ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'TENANT_ADMIN' AND r.tenant_id IS NULL
  AND p.name IN ('geofences:create','geofences:read','geofences:update','geofences:delete')
ON CONFLICT DO NOTHING;

-- HR_MANAGER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'HR_MANAGER' AND r.tenant_id IS NULL
  AND p.name IN ('geofences:create','geofences:read','geofences:update','geofences:delete')
ON CONFLICT DO NOTHING;

-- SITE_SUPERVISOR — read only
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'SITE_SUPERVISOR' AND r.tenant_id IS NULL
  AND p.name IN ('geofences:read')
ON CONFLICT DO NOTHING;
