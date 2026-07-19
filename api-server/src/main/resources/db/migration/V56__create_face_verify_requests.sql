CREATE TABLE face_verify_requests (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenants(id),
    employee_id       UUID NOT NULL REFERENCES employees(id),
    status            VARCHAR(10) NOT NULL DEFAULT 'pending'
                          CHECK (status IN ('pending', 'pass', 'fail')),
    face_verified     BOOLEAN,
    liveness_verified BOOLEAN,
    face_verify_score DOUBLE PRECISION,
    error_code        VARCHAR(30),
    requires_liveness BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at        TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_face_verify_requests_tenant_employee
    ON face_verify_requests(tenant_id, employee_id);
