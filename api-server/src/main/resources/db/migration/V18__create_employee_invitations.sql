-- Employee invitations: HR/Admin sends an email invite to onboard an employee.

CREATE TABLE employee_invitations (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email       VARCHAR(255) NOT NULL,
    token       UUID NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    status      VARCHAR(20) NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'accepted', 'cancelled', 'expired')),
    invited_by  UUID NOT NULL REFERENCES users(id),
    role_id     UUID REFERENCES roles(id),
    first_name  VARCHAR(100),
    last_name   VARCHAR(100),
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ
);

CREATE INDEX idx_emp_inv_tenant_id ON employee_invitations(tenant_id);
CREATE INDEX idx_emp_inv_email     ON employee_invitations(email);
CREATE INDEX idx_emp_inv_token     ON employee_invitations(token);
CREATE INDEX idx_emp_inv_status    ON employee_invitations(status);
