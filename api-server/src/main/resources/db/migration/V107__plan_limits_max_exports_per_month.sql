-- #135 (2026-08-19): "export chưa được kiểm tra limit" — no export-specific limit field existed
-- anywhere in plan_limits, so there was nothing to enforce against. Seeded proportionally to the
-- existing max_random_checks_per_month tier ratios (10/100/1000/unlimited) since both are
-- "resource-intensive action per month" style limits already established in this schema.
ALTER TABLE plan_limits
    ADD COLUMN max_exports_per_month INT;

UPDATE plan_limits SET max_exports_per_month = 10
    WHERE plan_id = (SELECT id FROM plans WHERE name = 'trial');
UPDATE plan_limits SET max_exports_per_month = 100
    WHERE plan_id = (SELECT id FROM plans WHERE name = 'basic');
UPDATE plan_limits SET max_exports_per_month = 1000
    WHERE plan_id = (SELECT id FROM plans WHERE name = 'pro');
-- enterprise stays NULL (unlimited), matching every other limit column for that plan.
