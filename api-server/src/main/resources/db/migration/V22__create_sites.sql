-- Sites (construction sites / attendance locations) scoped to a tenant.
-- Geofence polygon is stored separately in task 56 (geofences table).

CREATE TABLE sites (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    code        VARCHAR(50),
    description TEXT,
    address     TEXT,
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    timezone    VARCHAR(50) NOT NULL DEFAULT 'UTC',
    status      VARCHAR(20) NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'inactive')),
    created_by  UUID REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ
);

-- Site code unique per tenant (ignoring soft-deleted)
CREATE UNIQUE INDEX uq_sites_code_tenant
    ON sites(tenant_id, code)
    WHERE code IS NOT NULL AND deleted_at IS NULL;

-- Site name unique per tenant (ignoring soft-deleted)
CREATE UNIQUE INDEX uq_sites_name_tenant
    ON sites(tenant_id, lower(name))
    WHERE deleted_at IS NULL;

CREATE INDEX idx_sites_tenant_id ON sites(tenant_id);
CREATE INDEX idx_sites_status    ON sites(status);

-- ── Grant sites permissions to HR_MANAGER (missing from V13) ────────────────

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN (
    'sites:create', 'sites:read', 'sites:update', 'sites:delete', 'sites:list'
)
WHERE r.name = 'HR_MANAGER' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;
