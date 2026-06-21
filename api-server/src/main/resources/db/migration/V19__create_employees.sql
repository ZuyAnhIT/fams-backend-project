-- Core employee HR profile, one record per employee per tenant.
-- user_id links to the auth user once the invitation is accepted or when created manually.

CREATE TABLE employees (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id         UUID REFERENCES users(id),
    employee_code   VARCHAR(50),
    first_name      VARCHAR(100) NOT NULL,
    last_name       VARCHAR(100) NOT NULL,
    email           VARCHAR(255),
    phone           VARCHAR(30),
    position        VARCHAR(100),
    department      VARCHAR(100),
    status          VARCHAR(20) NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active', 'inactive', 'terminated')),
    hired_date      DATE,
    avatar_url      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ
);

-- One employee profile per user per tenant
CREATE UNIQUE INDEX uq_employees_user_tenant
    ON employees(tenant_id, user_id)
    WHERE user_id IS NOT NULL AND deleted_at IS NULL;

-- Unique employee code per tenant
CREATE UNIQUE INDEX uq_employees_code_tenant
    ON employees(tenant_id, employee_code)
    WHERE employee_code IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX idx_employees_tenant_id  ON employees(tenant_id);
CREATE INDEX idx_employees_user_id    ON employees(user_id);
CREATE INDEX idx_employees_status     ON employees(status);
CREATE INDEX idx_employees_email      ON employees(email);
CREATE INDEX idx_employees_department ON employees(department);
