-- ============================================================
-- Dọn dẹp bộ dữ liệu hiệu năng (scripts/seed_perf.sql)
-- ============================================================
-- Xóa toàn bộ tenant/user do seed_perf.sql sinh ra, đưa database về lại trạng thái chỉ còn
-- bộ demo chức năng sạch (18 tenant từ scripts/seed.sh). An toàn: chỉ động tới dữ liệu có
-- slug/email bắt đầu bằng "perf-"/"perf." — không đụng tới bất kỳ tenant demo nào khác.

BEGIN;

DELETE FROM checkins    WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'perf-tenant-%');
DELETE FROM assignments WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'perf-tenant-%');
DELETE FROM user_roles  WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'perf-tenant-%');
DELETE FROM shifts      WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'perf-tenant-%');
DELETE FROM employees   WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'perf-tenant-%');
DELETE FROM sites       WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'perf-tenant-%');
DELETE FROM tenant_subscriptions WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'perf-tenant-%');
DELETE FROM tenants     WHERE slug LIKE 'perf-tenant-%';
DELETE FROM users       WHERE email LIKE 'perf.%@perf.fams.local';

COMMIT;

SELECT 'remaining perf tenants' AS metric, count(*) AS value FROM tenants WHERE slug LIKE 'perf-tenant-%'
UNION ALL SELECT 'remaining perf users', count(*) FROM users WHERE email LIKE 'perf.%@perf.fams.local'
UNION ALL SELECT 'remaining demo tenants (should be 18)', count(*) FROM tenants WHERE deleted_at IS NULL;
