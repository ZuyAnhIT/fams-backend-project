ALTER TABLE billing_orders
    ADD COLUMN tenant_name_snapshot VARCHAR(255),
    ADD COLUMN invoice_status VARCHAR(30) NOT NULL DEFAULT 'NOT_ELIGIBLE',
    ADD COLUMN invoice_number VARCHAR(100),
    ADD COLUMN invoice_issued_at TIMESTAMPTZ,
    ADD COLUMN invoice_lookup_url TEXT;

UPDATE billing_orders orders
SET tenant_name_snapshot = tenants.name
FROM tenants
WHERE tenants.id = orders.tenant_id;

UPDATE billing_orders
SET tenant_name_snapshot = 'Công ty không xác định'
WHERE tenant_name_snapshot IS NULL OR BTRIM(tenant_name_snapshot) = '';

ALTER TABLE billing_orders
    ALTER COLUMN tenant_name_snapshot SET NOT NULL,
    ADD CONSTRAINT ck_billing_orders_invoice_status CHECK (invoice_status IN (
        'NOT_ELIGIBLE', 'PENDING_ISSUANCE', 'ISSUED', 'FAILED'
    ));

UPDATE billing_orders
SET invoice_status = 'PENDING_ISSUANCE'
WHERE status = 'PAID' AND invoice_status = 'NOT_ELIGIBLE';

CREATE INDEX idx_billing_orders_tenant_name_created
    ON billing_orders (LOWER(tenant_name_snapshot), created_at DESC);
