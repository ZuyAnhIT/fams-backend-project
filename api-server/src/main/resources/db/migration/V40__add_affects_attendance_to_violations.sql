ALTER TABLE violations
    ADD COLUMN affects_attendance BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_violations_affects_attendance ON violations (tenant_id, affects_attendance) WHERE affects_attendance = TRUE;
