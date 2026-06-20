-- Add platform admin flag to users
ALTER TABLE users ADD COLUMN is_platform_admin BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE users SET is_platform_admin = TRUE WHERE email = 'admin@fams.com';

-- Create tenants table
CREATE TABLE tenants (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL,
    domain          VARCHAR(255),
    logo_url        TEXT,
    industry        VARCHAR(100),
    country_code    CHAR(2),
    timezone        VARCHAR(100) NOT NULL DEFAULT 'UTC',
    locale          VARCHAR(10)  NOT NULL DEFAULT 'en',
    currency_code   CHAR(3)      NOT NULL DEFAULT 'USD',
    status          VARCHAR(20)  NOT NULL DEFAULT 'trial',
    owner_id        UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_tenants_slug   ON tenants(slug)   WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX idx_tenants_domain ON tenants(domain) WHERE deleted_at IS NULL AND domain IS NOT NULL;
CREATE INDEX idx_tenants_owner_id ON tenants(owner_id);
CREATE INDEX idx_tenants_status   ON tenants(status) WHERE deleted_at IS NULL;
