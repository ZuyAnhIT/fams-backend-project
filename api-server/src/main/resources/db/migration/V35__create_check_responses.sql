CREATE TABLE check_responses (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    scheduled_check_id UUID NOT NULL REFERENCES scheduled_checks(id),
    employee_id     UUID NOT NULL,
    responded_at    TIMESTAMPTZ NOT NULL,
    latitude        NUMERIC(10, 7) NOT NULL,
    longitude       NUMERIC(10, 7) NOT NULL,
    accuracy_meters NUMERIC(8, 2),
    face_image_url  TEXT,
    liveness_score  NUMERIC(5, 4),
    location_verified  BOOLEAN NOT NULL DEFAULT FALSE,
    face_verified      BOOLEAN,
    liveness_verified  BOOLEAN,
    outcome         VARCHAR(10) NOT NULL CHECK (outcome IN ('pass', 'fail')),
    failure_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_check_responses_scheduled_check
    ON check_responses (scheduled_check_id);

CREATE INDEX idx_check_responses_tenant_employee
    ON check_responses (tenant_id, employee_id);
