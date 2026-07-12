CREATE TABLE audit_logs (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id     UUID REFERENCES tenants(id),
    actor_id      UUID REFERENCES users(id),
    actor_email   VARCHAR(255),
    entity_type   VARCHAR(100) NOT NULL,
    entity_id     VARCHAR(255),
    action        VARCHAR(100) NOT NULL,
    old_value     JSONB,
    new_value     JSONB,
    request_id    VARCHAR(100),
    ip_address    VARCHAR(64),
    user_agent    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_tenant_id       ON audit_logs(tenant_id);
CREATE INDEX idx_audit_logs_actor_id        ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_entity_type     ON audit_logs(entity_type);
CREATE INDEX idx_audit_logs_entity_id       ON audit_logs(entity_id);
CREATE INDEX idx_audit_logs_action          ON audit_logs(action);
CREATE INDEX idx_audit_logs_request_id      ON audit_logs(request_id);
CREATE INDEX idx_audit_logs_created_at      ON audit_logs(created_at DESC);
