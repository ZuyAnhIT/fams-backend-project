-- Employee assignments: link an employee to a site for a given shift and date range.
-- Permissions (assignments:create/read/update/delete/list) are already seeded in V13.

CREATE TABLE assignments (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id)   ON DELETE CASCADE,
    site_id         UUID        NOT NULL REFERENCES sites(id)     ON DELETE CASCADE,
    employee_id     UUID        NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    shift_id        UUID        REFERENCES shifts(id)             ON DELETE SET NULL,
    start_date      DATE        NOT NULL,
    end_date        DATE,
    role            VARCHAR(50) NOT NULL DEFAULT 'worker',
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    notes           TEXT,
    created_by      UUID        REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT assignments_status_chk CHECK (status IN ('active', 'cancelled')),
    CONSTRAINT assignments_role_chk   CHECK (role   IN ('worker', 'supervisor')),
    CONSTRAINT assignments_dates_chk  CHECK (end_date IS NULL OR end_date >= start_date)
);

-- An employee may only have one active assignment per site at a time
CREATE UNIQUE INDEX uq_assignments_employee_site_active
    ON assignments(employee_id, site_id)
    WHERE status = 'active' AND deleted_at IS NULL;

CREATE INDEX idx_assignments_tenant_id   ON assignments(tenant_id);
CREATE INDEX idx_assignments_site_id     ON assignments(site_id);
CREATE INDEX idx_assignments_employee_id ON assignments(employee_id);
CREATE INDEX idx_assignments_shift_id    ON assignments(shift_id);
CREATE INDEX idx_assignments_status      ON assignments(status) WHERE deleted_at IS NULL;

-- Grant SITE_SUPERVISOR read + list on assignments (missing from V13)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'SITE_SUPERVISOR' AND r.tenant_id IS NULL
  AND p.name IN ('assignments:read', 'assignments:list')
ON CONFLICT DO NOTHING;
