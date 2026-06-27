-- Checkins: one record per attendance session (check-in + check-out pair).
-- check_out_at is NULL until the employee checks out.
-- Permissions (checkins:create/read/list) are already seeded in V13.

CREATE TABLE checkins (
    id                      UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id               UUID            NOT NULL REFERENCES tenants(id)     ON DELETE CASCADE,
    site_id                 UUID            NOT NULL REFERENCES sites(id)        ON DELETE CASCADE,
    employee_id             UUID            NOT NULL REFERENCES employees(id)    ON DELETE CASCADE,
    assignment_id           UUID            NOT NULL REFERENCES assignments(id)  ON DELETE CASCADE,
    shift_id                UUID            REFERENCES shifts(id)                ON DELETE SET NULL,

    -- Status: valid | pending_review | rejected
    status                  VARCHAR(20)     NOT NULL DEFAULT 'valid'
                                CHECK (status IN ('valid', 'pending_review', 'rejected')),

    -- Check-in fields
    check_in_at             TIMESTAMPTZ     NOT NULL,
    check_in_lat            DOUBLE PRECISION NOT NULL,
    check_in_lon            DOUBLE PRECISION NOT NULL,
    check_in_accuracy       DOUBLE PRECISION,
    check_in_inside_geofence BOOLEAN        NOT NULL DEFAULT TRUE,

    -- Check-out fields (filled on checkout)
    check_out_at            TIMESTAMPTZ,
    check_out_lat           DOUBLE PRECISION,
    check_out_lon           DOUBLE PRECISION,
    check_out_accuracy      DOUBLE PRECISION,
    check_out_inside_geofence BOOLEAN,

    -- Computed at checkout
    work_minutes            INTEGER,

    -- Risk / metadata
    gps_risk_score          DOUBLE PRECISION NOT NULL DEFAULT 0,
    device_id               VARCHAR(255),
    note                    TEXT,

    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deleted_at              TIMESTAMPTZ
);

-- An employee can only have one open check-in (no check_out_at) per assignment at a time
CREATE UNIQUE INDEX uq_checkins_open_session
    ON checkins(assignment_id)
    WHERE check_out_at IS NULL AND deleted_at IS NULL;

CREATE INDEX idx_checkins_tenant_id    ON checkins(tenant_id);
CREATE INDEX idx_checkins_site_id      ON checkins(site_id);
CREATE INDEX idx_checkins_employee_id  ON checkins(employee_id);
CREATE INDEX idx_checkins_assignment_id ON checkins(assignment_id);
CREATE INDEX idx_checkins_check_in_at  ON checkins(check_in_at);
CREATE INDEX idx_checkins_status       ON checkins(status) WHERE deleted_at IS NULL;
