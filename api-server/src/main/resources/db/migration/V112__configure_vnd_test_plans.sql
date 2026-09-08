-- PayOS settles in VND and requires whole-number amounts. Keep the free trial for
-- tenant onboarding, then expose four intentionally low-priced paid tiers for
-- end-to-end production-like payment testing.

UPDATE plans
SET display_name = 'Cơ bản',
    description = 'Các tính năng thiết yếu cho đội nhóm nhỏ.',
    price_monthly = 20000,
    price_yearly = 200000,
    sort_order = 3,
    updated_at = now()
WHERE name = 'basic' AND deleted_at IS NULL;

UPDATE plans
SET display_name = 'Chuyên nghiệp',
    description = 'Mở rộng quy mô vận hành với hạn mức cao hơn.',
    price_monthly = 30000,
    price_yearly = 300000,
    sort_order = 4,
    updated_at = now()
WHERE name = 'pro' AND deleted_at IS NULL;

UPDATE plans
SET display_name = 'Doanh nghiệp',
    description = 'Hạn mức không giới hạn cho doanh nghiệp lớn.',
    price_monthly = 40000,
    price_yearly = 400000,
    sort_order = 5,
    updated_at = now()
WHERE name = 'enterprise' AND deleted_at IS NULL;

INSERT INTO plans (
    name, display_name, description, price_monthly, price_yearly, sort_order, is_active
)
SELECT
    'starter', 'Khởi đầu', 'Phù hợp để bắt đầu quản lý một đội nhóm nhỏ.',
    10000, 100000, 2, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM plans WHERE name = 'starter' AND deleted_at IS NULL
);

INSERT INTO plan_limits (
    plan_id, max_employees, max_sites, max_storage_gb,
    max_random_checks_per_month, max_exports_per_month
)
SELECT id, 20, 2, 5, 50, 30
FROM plans
WHERE name = 'starter' AND deleted_at IS NULL
ON CONFLICT (plan_id) DO NOTHING;
