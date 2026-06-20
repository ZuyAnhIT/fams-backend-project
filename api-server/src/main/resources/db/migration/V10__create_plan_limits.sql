CREATE TABLE plan_limits (
    id                          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    plan_id                     UUID NOT NULL UNIQUE REFERENCES plans(id),
    max_employees               INT,
    max_sites                   INT,
    max_storage_gb              INT,
    max_random_checks_per_month INT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_plan_limits_plan_id ON plan_limits(plan_id);

-- Seed limits for the four default plans (NULL = unlimited)
INSERT INTO plan_limits (plan_id, max_employees, max_sites, max_storage_gb, max_random_checks_per_month)
SELECT id, 5, 1, 1, 10 FROM plans WHERE name = 'trial';

INSERT INTO plan_limits (plan_id, max_employees, max_sites, max_storage_gb, max_random_checks_per_month)
SELECT id, 50, 5, 10, 100 FROM plans WHERE name = 'basic';

INSERT INTO plan_limits (plan_id, max_employees, max_sites, max_storage_gb, max_random_checks_per_month)
SELECT id, 500, 50, 100, 1000 FROM plans WHERE name = 'pro';

INSERT INTO plan_limits (plan_id, max_employees, max_sites, max_storage_gb, max_random_checks_per_month)
SELECT id, NULL, NULL, NULL, NULL FROM plans WHERE name = 'enterprise';
