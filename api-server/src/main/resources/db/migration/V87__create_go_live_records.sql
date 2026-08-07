-- FE feedback (2026-08-06, UAT/Go-live story): the UAT checklist (docs/testing/manual-test-
-- scenarios.md §B.8, docs/deployment/go-live-checklist.md) only ever existed as a markdown
-- document — no way to persist a formal go-live sign-off record per tenant (who tested, on what
-- build, which steps passed/failed, what evidence, who approved). Enterprise customers commonly
-- require exactly this as a compliance artifact before a tenant is considered production-ready.

CREATE TABLE go_live_records (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    environment         VARCHAR(50) NOT NULL,
    build_version       VARCHAR(100) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    steps               JSONB NOT NULL DEFAULT '[]'::jsonb,
    performed_by        UUID NOT NULL,
    performed_by_name   VARCHAR(255),
    started_at          TIMESTAMPTZ NOT NULL,
    completed_at        TIMESTAMPTZ,
    approved_by         UUID,
    approved_by_name    VARCHAR(255),
    approved_at         TIMESTAMPTZ,
    approval_note       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX idx_go_live_records_tenant ON go_live_records(tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_go_live_records_status ON go_live_records(status) WHERE deleted_at IS NULL;

INSERT INTO permissions (name, resource, action, description) VALUES
    ('golive:manage', 'golive', 'manage', 'Create/update go-live checklist records and approve tenant go-live sign-off')
ON CONFLICT (name) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'PLATFORM_ADMIN' AND r.tenant_id IS NULL AND p.name = 'golive:manage'
ON CONFLICT DO NOTHING;
