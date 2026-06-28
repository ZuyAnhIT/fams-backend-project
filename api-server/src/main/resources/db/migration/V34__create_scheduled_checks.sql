-- Scheduled random check instances generated at shift start.
-- One row per individual check dispatch (checks_per_shift rows per assignment per day).
CREATE TABLE scheduled_checks (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id        UUID NOT NULL REFERENCES tenants(id),
    assignment_id    UUID NOT NULL REFERENCES assignments(id),
    employee_id      UUID NOT NULL REFERENCES employees(id),
    site_id          UUID NOT NULL REFERENCES sites(id),
    shift_id         UUID REFERENCES shifts(id),
    config_id        UUID REFERENCES random_check_configs(id),
    config_snapshot  JSONB NOT NULL,               -- copy of config at generation time (task 97)
    check_date       DATE NOT NULL,                -- date for which this check was generated
    check_index      INTEGER NOT NULL DEFAULT 1,   -- 1-based position within the shift's checks
    scheduled_at     TIMESTAMPTZ NOT NULL,         -- when notification should be dispatched
    expires_at       TIMESTAMPTZ NOT NULL,         -- deadline for employee response
    status           VARCHAR(20) NOT NULL DEFAULT 'pending'
                         CHECK (status IN ('pending', 'sent', 'responded', 'no_response', 'cancelled')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at       TIMESTAMPTZ
);

CREATE INDEX idx_sc_tenant_id       ON scheduled_checks (tenant_id);
CREATE INDEX idx_sc_assignment_id   ON scheduled_checks (assignment_id);
CREATE INDEX idx_sc_employee_id     ON scheduled_checks (employee_id);
CREATE INDEX idx_sc_check_date      ON scheduled_checks (check_date);
CREATE INDEX idx_sc_status          ON scheduled_checks (status) WHERE deleted_at IS NULL;
CREATE INDEX idx_sc_scheduled_at    ON scheduled_checks (scheduled_at) WHERE deleted_at IS NULL;
-- Prevents regenerating checks for the same assignment on the same date
CREATE UNIQUE INDEX uq_sc_assignment_date_index
    ON scheduled_checks (assignment_id, check_date, check_index)
    WHERE deleted_at IS NULL;
