-- Random check configuration table
-- Stores both tenant-level defaults (site_id IS NULL) and site-level overrides (site_id IS NOT NULL).
CREATE TABLE random_check_configs (
    id                       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id                UUID NOT NULL REFERENCES tenants(id),
    site_id                  UUID REFERENCES sites(id),            -- NULL = tenant default
    checks_per_shift         INTEGER NOT NULL DEFAULT 1
                                 CHECK (checks_per_shift >= 1 AND checks_per_shift <= 10),
    min_interval_minutes     INTEGER NOT NULL DEFAULT 60
                                 CHECK (min_interval_minutes >= 0),
    allowed_start_time       TIME NOT NULL DEFAULT '08:00:00',
    allowed_end_time         TIME NOT NULL DEFAULT '17:00:00',
    check_mode               VARCHAR(30) NOT NULL DEFAULT 'location_only'
                                 CHECK (check_mode IN ('location_only', 'location_face', 'location_face_liveness')),
    applicable_roles         TEXT NOT NULL DEFAULT '',             -- comma-separated role names
    response_window_seconds  INTEGER NOT NULL DEFAULT 300
                                 CHECK (response_window_seconds >= 30),
    is_active                BOOLEAN NOT NULL DEFAULT TRUE,
    created_by               UUID NOT NULL REFERENCES users(id),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at               TIMESTAMPTZ
);

-- Only one active tenant-level default per tenant
CREATE UNIQUE INDEX uq_rcc_tenant_default
    ON random_check_configs (tenant_id)
    WHERE site_id IS NULL AND deleted_at IS NULL;

-- Only one active config per site
CREATE UNIQUE INDEX uq_rcc_site
    ON random_check_configs (tenant_id, site_id)
    WHERE site_id IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX idx_rcc_tenant_id  ON random_check_configs (tenant_id);
CREATE INDEX idx_rcc_site_id    ON random_check_configs (site_id);
CREATE INDEX idx_rcc_created_by ON random_check_configs (created_by);
