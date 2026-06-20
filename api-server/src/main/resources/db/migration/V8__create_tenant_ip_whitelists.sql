CREATE TABLE tenant_ip_whitelists (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    ip_address  VARCHAR(50)  NOT NULL,
    label       VARCHAR(100),
    scope       VARCHAR(20)  NOT NULL DEFAULT 'all',
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

CREATE INDEX idx_ip_whitelists_tenant_id ON tenant_ip_whitelists(tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_ip_whitelists_active    ON tenant_ip_whitelists(tenant_id, is_active) WHERE deleted_at IS NULL;
