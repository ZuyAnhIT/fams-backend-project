-- Managed departments per tenant
CREATE TABLE departments (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_departments_tenant_name
    ON departments(tenant_id, lower(name))
    WHERE deleted_at IS NULL;

CREATE INDEX idx_departments_tenant_id ON departments(tenant_id);

-- Employee code format settings stored in tenant_settings
ALTER TABLE tenant_settings
    ADD COLUMN employee_code_prefix  VARCHAR(20),
    ADD COLUMN employee_code_padding SMALLINT NOT NULL DEFAULT 4,
    ADD COLUMN employee_code_seq     BIGINT   NOT NULL DEFAULT 0;

-- Optional: link employees to a managed department
ALTER TABLE employees
    ADD COLUMN department_id UUID REFERENCES departments(id);

CREATE INDEX idx_employees_department_id ON employees(department_id);
