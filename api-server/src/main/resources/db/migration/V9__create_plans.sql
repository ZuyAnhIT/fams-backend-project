CREATE TABLE plans (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name          VARCHAR(50)   NOT NULL,
    display_name  VARCHAR(100)  NOT NULL,
    description   TEXT,
    price_monthly NUMERIC(10,2) NOT NULL DEFAULT 0,
    price_yearly  NUMERIC(10,2) NOT NULL DEFAULT 0,
    sort_order    INT           NOT NULL DEFAULT 0,
    is_active     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_plans_name ON plans(name) WHERE deleted_at IS NULL;
CREATE INDEX idx_plans_active ON plans(is_active) WHERE deleted_at IS NULL;

INSERT INTO plans (name, display_name, description, price_monthly, price_yearly, sort_order) VALUES
  ('trial',      'Trial',      'Free trial with limited features. No credit card required.', 0,      0,       1),
  ('basic',      'Basic',      'Essential features for small teams.',                         9.99,   99.99,   2),
  ('pro',        'Pro',        'Advanced features for growing businesses.',                   29.99,  299.99,  3),
  ('enterprise', 'Enterprise', 'Full-featured plan for large organisations with SLA support.',99.99,  999.99,  4);
