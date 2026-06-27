-- Daily attendance summary: one row per (tenant, employee, site, date).
-- Auto-created/updated on checkout and by the nightly catch-up job.
-- Tasks 81-84 will add late/early-leave/OT/missing-checkout columns via ALTER TABLE.

CREATE TABLE attendance_summaries (
    id                  UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID            NOT NULL REFERENCES tenants(id)     ON DELETE CASCADE,
    employee_id         UUID            NOT NULL REFERENCES employees(id)   ON DELETE CASCADE,
    site_id             UUID            NOT NULL REFERENCES sites(id)       ON DELETE CASCADE,
    shift_id            UUID            REFERENCES shifts(id)               ON DELETE SET NULL,
    assignment_id       UUID            REFERENCES assignments(id)          ON DELETE SET NULL,

    -- Date in the site's local timezone
    attendance_date     DATE            NOT NULL,

    first_checkin_at    TIMESTAMPTZ,
    last_checkout_at    TIMESTAMPTZ,

    -- Sum of work_minutes from all sessions for this day
    total_work_minutes  INTEGER         NOT NULL DEFAULT 0,

    -- Number of check-in sessions for this day
    session_count       INTEGER         NOT NULL DEFAULT 0,

    -- present = all sessions completed; incomplete = at least one open session
    status              VARCHAR(20)     NOT NULL DEFAULT 'present'
                            CHECK (status IN ('present', 'incomplete')),

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ,

    -- One summary per employee per site per day within a tenant
    UNIQUE (tenant_id, employee_id, site_id, attendance_date)
);

CREATE INDEX idx_att_summaries_tenant_id   ON attendance_summaries(tenant_id);
CREATE INDEX idx_att_summaries_employee_id ON attendance_summaries(employee_id);
CREATE INDEX idx_att_summaries_site_id     ON attendance_summaries(site_id);
CREATE INDEX idx_att_summaries_date        ON attendance_summaries(attendance_date);
CREATE INDEX idx_att_summaries_status      ON attendance_summaries(status) WHERE deleted_at IS NULL;
