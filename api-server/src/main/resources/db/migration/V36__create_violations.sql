CREATE TABLE violations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    employee_id         UUID NOT NULL,
    site_id             UUID NOT NULL,
    scheduled_check_id  UUID REFERENCES scheduled_checks(id),
    check_response_id   UUID REFERENCES check_responses(id),
    violation_type      VARCHAR(30) NOT NULL
                            CHECK (violation_type IN ('no_response', 'location_fail', 'face_fail', 'liveness_fail')),
    check_date          DATE NOT NULL,
    description         TEXT,
    resolved            BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_at         TIMESTAMPTZ,
    resolved_by         UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX idx_violations_tenant_employee ON violations (tenant_id, employee_id);
CREATE INDEX idx_violations_tenant_date     ON violations (tenant_id, check_date);
CREATE INDEX idx_violations_scheduled_check ON violations (scheduled_check_id) WHERE scheduled_check_id IS NOT NULL;
