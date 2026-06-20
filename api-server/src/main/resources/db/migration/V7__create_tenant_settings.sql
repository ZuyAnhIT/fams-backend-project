CREATE TABLE tenant_settings (
    id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id             UUID NOT NULL UNIQUE REFERENCES tenants(id),
    date_format           VARCHAR(30)  NOT NULL DEFAULT 'DD/MM/YYYY',
    time_format           VARCHAR(20)  NOT NULL DEFAULT 'HH:mm',
    brand_primary_color   VARCHAR(7),
    brand_secondary_color VARCHAR(7),
    brand_accent_color    VARCHAR(7),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_tenant_settings_tenant_id ON tenant_settings(tenant_id);
