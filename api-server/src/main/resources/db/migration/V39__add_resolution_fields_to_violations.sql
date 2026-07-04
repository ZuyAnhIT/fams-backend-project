ALTER TABLE violations
    ADD COLUMN resolution        VARCHAR(20)
        CHECK (resolution IN ('confirmed', 'dismissed')),
    ADD COLUMN resolution_reason TEXT,
    ADD COLUMN updated_at        TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_violations_resolution ON violations (tenant_id, resolution) WHERE resolution IS NOT NULL;
