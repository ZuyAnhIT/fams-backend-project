CREATE TABLE tenant_subscriptions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenants(id),
    plan_id       UUID NOT NULL REFERENCES plans(id),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    billing_cycle VARCHAR(10)  NOT NULL DEFAULT 'MONTHLY',
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenant_subscriptions_tenant UNIQUE (tenant_id)
);
