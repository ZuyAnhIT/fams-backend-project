CREATE TABLE notification_templates (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    event_type VARCHAR(100) NOT NULL,
    locale VARCHAR(10) NOT NULL DEFAULT 'vi',
    title_template VARCHAR(500) NOT NULL,
    body_template TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE(tenant_id, event_type, locale)
);
CREATE INDEX idx_notif_templates_tenant_event ON notification_templates(tenant_id, event_type, locale);
