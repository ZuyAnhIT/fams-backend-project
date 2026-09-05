-- Keep the live SaaS catalogue strictly in VND and remove the last legacy USD fixture.
-- The legacy plan still has one sample tenant subscription but no billing orders, so move
-- that subscription to the current Basic plan before archiving the obsolete plan row.
DO $$
DECLARE
    legacy_plan_id UUID;
    basic_plan_id UUID;
BEGIN
    SELECT id INTO legacy_plan_id
    FROM plans
    WHERE name = 'legacy_basic' AND deleted_at IS NULL
    LIMIT 1;

    IF legacy_plan_id IS NOT NULL THEN
        SELECT id INTO basic_plan_id
        FROM plans
        WHERE name = 'basic' AND deleted_at IS NULL
        LIMIT 1;

        IF basic_plan_id IS NULL THEN
            RAISE EXCEPTION 'Cannot archive legacy_basic because the VND basic plan is missing';
        END IF;

        UPDATE tenant_subscriptions
        SET plan_id = basic_plan_id,
            updated_at = NOW()
        WHERE plan_id = legacy_plan_id;

        UPDATE plans
        SET is_active = FALSE,
            deleted_at = NOW(),
            updated_at = NOW()
        WHERE id = legacy_plan_id;
    END IF;
END $$;

UPDATE tenants
SET currency_code = 'VND',
    updated_at = NOW()
WHERE deleted_at IS NULL
  AND currency_code <> 'VND';

ALTER TABLE tenants ALTER COLUMN currency_code SET DEFAULT 'VND';
