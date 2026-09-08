CREATE SEQUENCE billing_order_code_seq START WITH 100000 INCREMENT BY 1;

CREATE TABLE billing_orders (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_code              BIGINT       NOT NULL UNIQUE,
    tenant_id               UUID         NOT NULL REFERENCES tenants(id),
    plan_id                 UUID         NOT NULL REFERENCES plans(id),
    plan_name_snapshot      VARCHAR(50)  NOT NULL,
    plan_display_snapshot   VARCHAR(100) NOT NULL,
    billing_cycle           VARCHAR(10)  NOT NULL,
    amount                  BIGINT       NOT NULL CHECK (amount > 0),
    amount_paid             BIGINT       NOT NULL DEFAULT 0 CHECK (amount_paid >= 0),
    currency                VARCHAR(3)   NOT NULL DEFAULT 'VND',
    status                  VARCHAR(20)  NOT NULL,
    payment_link_id         VARCHAR(100),
    checkout_url            TEXT,
    qr_code                 TEXT,
    payment_reference       VARCHAR(150),
    provider_status         VARCHAR(30),
    failure_reason          VARCHAR(500),
    created_by              UUID         NOT NULL REFERENCES users(id),
    expires_at              TIMESTAMPTZ  NOT NULL,
    paid_at                 TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    subscription_applied_at TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_billing_orders_cycle CHECK (billing_cycle IN ('MONTHLY', 'YEARLY')),
    CONSTRAINT ck_billing_orders_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_billing_orders_status CHECK (status IN (
        'CREATING', 'PENDING', 'PROCESSING', 'UNDERPAID',
        'PAID', 'CANCELLED', 'EXPIRED', 'FAILED'
    ))
);

CREATE UNIQUE INDEX uq_billing_orders_payment_link
    ON billing_orders(payment_link_id) WHERE payment_link_id IS NOT NULL;
CREATE UNIQUE INDEX uq_billing_orders_payment_reference
    ON billing_orders(payment_reference) WHERE payment_reference IS NOT NULL;
CREATE UNIQUE INDEX uq_billing_orders_open_tenant
    ON billing_orders(tenant_id)
    WHERE status IN ('CREATING', 'PENDING', 'PROCESSING', 'UNDERPAID');
CREATE INDEX idx_billing_orders_tenant_created
    ON billing_orders(tenant_id, created_at DESC);
CREATE INDEX idx_billing_orders_status_updated
    ON billing_orders(status, updated_at);

INSERT INTO permissions (name, resource, action, description)
SELECT v.name, 'billing', v.action, v.description
FROM (VALUES
    ('billing:list',   'list',   'List payment orders across the platform'),
    ('billing:read',   'read',   'View payment order details'),
    ('billing:update', 'update', 'Cancel or reconcile payment orders')
) AS v(name, action, description)
WHERE NOT EXISTS (SELECT 1 FROM permissions p WHERE p.name = v.name);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('billing:list', 'billing:read', 'billing:update')
WHERE r.tenant_id IS NULL
  AND r.name IN ('PLATFORM_ADMIN', 'PLATFORM_BILLING_OPS')
ON CONFLICT DO NOTHING;
