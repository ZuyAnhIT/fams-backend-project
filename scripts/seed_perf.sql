-- ============================================================
-- FAMS Performance/Load Test Dataset (scripts/seed_perf.sql)
-- ============================================================
-- Bộ dữ liệu HIỆU NĂNG — TÁCH BIỆT HOÀN TOÀN khỏi bộ demo chức năng
-- (scripts/seed.sh + scripts/seed_historical.sql), đúng theo
-- docs/testing/sample-data-requirements-v2.md mục 20.4.
--
-- CẢNH BÁO QUAN TRỌNG:
--   - Script này insert THẲNG bằng SQL (KHÔNG qua API), nên KHÔNG đi qua bất kỳ business
--     validation/permission check nào của backend — chỉ dùng để tạo VOLUME dữ liệu phục vụ
--     test hiệu năng (danh sách, tìm kiếm, phân trang, báo cáo), KHÔNG dùng để test nghiệp vụ.
--   - Chạy script này SẼ LÀM BẨN bộ dữ liệu demo 18-tenant sạch nếu chạy chung 1 database với
--     scripts/seed.sh. Khuyến nghị: chạy trên 1 database RIÊNG cho test hiệu năng, hoặc chấp
--     nhận phải reseed lại bộ demo sạch (scripts/seed.sh) sau khi test hiệu năng xong.
--   - Toàn bộ tenant/user sinh ra bởi script này có slug/email bắt đầu bằng "perf-"/"perf."
--     để dễ nhận diện và dọn dẹp riêng — xem scripts/seed_perf_cleanup.sql.
--
-- Quy mô mặc định (chỉnh số lượng bằng cách sửa các hằng số trong CTE `params` bên dưới):
--   150 tenant hiệu năng (trong khoảng yêu cầu 100-500)
--   2 "mega tenant" (perf-tenant-0001, perf-tenant-0002) — mỗi tenant ~2.500 nhân viên
--     (đúng yêu cầu "1.000-5.000 nhân viên trong MỘT công ty")
--   148 tenant còn lại — 60-200 nhân viên/tenant (biến thiên theo hash)
--   ~40% nhân viên có tài khoản đăng nhập (users) → tổng trong khoảng 5.000-10.000 user
--   3-8 site/tenant, 1-2 ca/site
--   1 assignment/nhân viên
--   Checkin: mega tenant ~90 ngày làm việc gần nhất/nhân viên, tenant thường ~60 ngày
--     → tổng ước tính ~1.2-1.5 triệu dòng checkins (xem số liệu thật ở cuối lần chạy)
--
-- Mật khẩu: toàn bộ user sinh ra dùng CHUNG password_hash với admin@fams.com (Admin@1234),
-- nhất quán quy ước mật khẩu chung của toàn bộ dữ liệu mẫu.
--
-- An toàn khi chạy lại nhiều lần: dùng ON CONFLICT DO NOTHING / NOT EXISTS ở những chỗ có
-- unique constraint tự nhiên (slug, email); các bảng không có unique tự nhiên (checkins,
-- assignments) sẽ SINH TRÙNG nếu chạy lại — do đó chỉ nên chạy 1 LẦN trên 1 database sạch,
-- không thiết kế idempotent như seed.sh/seed_historical.sql (mục đích khác nhau: đây là nạp
-- volume 1 lần cho 1 đợt test hiệu năng, không phải dataset tái sử dụng liên tục).
-- ============================================================

BEGIN;

-- ── Tham số quy mô ─────────────────────────────────────────────────────────────
CREATE TEMP TABLE perf_params AS
SELECT
  150   AS tenant_count,
  2     AS mega_tenant_count,
  2500  AS mega_employee_count,
  60    AS normal_employee_min,
  200   AS normal_employee_max,
  90    AS mega_checkin_days,
  60    AS normal_checkin_days;

-- ── 1. Owner users cho mỗi perf tenant ───────────────────────────────────────────
CREATE TEMP TABLE perf_owner_users AS
SELECT
  gen_random_uuid() AS id,
  'perf.owner' || lpad(n::text, 4, '0') || '@perf.fams.local' AS email,
  (SELECT password_hash FROM users WHERE email = 'admin@fams.com' LIMIT 1) AS password_hash,
  'Perf Owner ' || lpad(n::text, 4, '0') AS display_name,
  n AS tenant_seq
FROM generate_series(1, (SELECT tenant_count FROM perf_params)) AS n;

INSERT INTO users (id, email, password_hash, display_name, is_active, email_verified, is_platform_admin, created_at, updated_at)
SELECT id, email, password_hash, display_name, TRUE, TRUE, FALSE, NOW() - '365 days'::INTERVAL, NOW()
FROM perf_owner_users
ON CONFLICT (email) DO NOTHING;

-- ── 2. Tenants ────────────────────────────────────────────────────────────────
CREATE TEMP TABLE perf_tenants AS
SELECT
  gen_random_uuid() AS id,
  'perf-tenant-' || lpad(pou.tenant_seq::text, 4, '0') AS slug,
  'Perf Test Company ' || lpad(pou.tenant_seq::text, 4, '0') AS name,
  pou.id AS owner_id,
  pou.tenant_seq AS tenant_seq,
  (pou.tenant_seq <= (SELECT mega_tenant_count FROM perf_params)) AS is_mega
FROM perf_owner_users pou;

INSERT INTO tenants (id, name, slug, industry, country_code, timezone, locale, currency_code, status, owner_id, created_at, updated_at)
SELECT id, name, slug, 'Performance Testing', 'VN', 'Asia/Ho_Chi_Minh', 'vi', 'VND', 'active', owner_id,
       NOW() - ((tenant_seq % 300) || ' days')::INTERVAL, NOW()
FROM perf_tenants
ON CONFLICT (slug) WHERE deleted_at IS NULL DO NOTHING;

-- ── 3. Subscriptions — mega tenants trên enterprise (không giới hạn), còn lại basic/pro xen kẽ
INSERT INTO tenant_subscriptions (id, tenant_id, plan_id, status, billing_cycle, started_at, created_at, updated_at)
SELECT
  gen_random_uuid(), pt.id,
  CASE
    WHEN pt.is_mega THEN (SELECT id FROM plans WHERE name = 'enterprise')
    WHEN pt.tenant_seq % 2 = 0 THEN (SELECT id FROM plans WHERE name = 'pro')
    ELSE (SELECT id FROM plans WHERE name = 'basic')
  END,
  'ACTIVE', 'MONTHLY', NOW() - '300 days'::INTERVAL, NOW(), NOW()
FROM perf_tenants pt
ON CONFLICT (tenant_id) DO NOTHING;

-- ── 4. Sites — 3-8/tenant, tọa độ rải quanh Hà Nội/TP.HCM/Đà Nẵng để có tính thực tế tối thiểu
CREATE TEMP TABLE perf_sites AS
SELECT
  gen_random_uuid() AS id,
  pt.id AS tenant_id,
  pt.tenant_seq,
  s.site_idx,
  'Site ' || pt.tenant_seq || '-' || s.site_idx AS name,
  'PERF-' || pt.tenant_seq || '-' || s.site_idx AS code,
  (10.5 + (hashtext(pt.id::text || s.site_idx::text) % 1000) / 1000.0) AS lat,
  (105.5 + (hashtext(pt.id::text || s.site_idx::text || 'lon') % 1000) / 1000.0) AS lon
FROM perf_tenants pt
CROSS JOIN LATERAL generate_series(1, 3 + (((hashtext(pt.id::text) % 6) + 6) % 6)) AS s(site_idx);

INSERT INTO sites (id, tenant_id, name, code, address, latitude, longitude, timezone, status, created_at, updated_at)
SELECT id, tenant_id, name, code, 'Địa chỉ demo hiệu năng', lat, lon, 'Asia/Ho_Chi_Minh', 'active', NOW(), NOW()
FROM perf_sites;

-- ── 5. Shifts — 1-2/site
CREATE TEMP TABLE perf_shifts AS
SELECT
  gen_random_uuid() AS id,
  ps.id AS site_id,
  ps.tenant_id,
  ps.tenant_seq,
  ps.site_idx,
  sh.shift_idx,
  CASE sh.shift_idx WHEN 1 THEN 'Ca hành chính' ELSE 'Ca tối' END AS name,
  CASE sh.shift_idx WHEN 1 THEN '08:00'::TIME ELSE '14:00'::TIME END AS start_time,
  CASE sh.shift_idx WHEN 1 THEN '17:00'::TIME ELSE '22:00'::TIME END AS end_time
FROM perf_sites ps
CROSS JOIN LATERAL generate_series(1, 1 + (((hashtext(ps.id::text || 'sh') % 2) + 2) % 2)) AS sh(shift_idx);

INSERT INTO shifts (id, site_id, tenant_id, name, start_time, end_time, allow_overnight, status, created_at, updated_at)
SELECT id, site_id, tenant_id, name, start_time, end_time, FALSE, 'active', NOW(), NOW()
FROM perf_shifts;

-- Chọn 1 shift đại diện mỗi site (shift_idx=1) để gán mặc định cho assignment/employee bên dưới
CREATE TEMP TABLE perf_site_default_shift AS
SELECT site_id, id AS shift_id, tenant_id, tenant_seq, site_idx
FROM perf_shifts WHERE shift_idx = 1;

-- ── 6. Employees — 2 mega tenant ~2.500/tenant, 148 tenant thường 60-200/tenant (theo hash) ──
CREATE TEMP TABLE perf_employees AS
SELECT
  gen_random_uuid() AS id,
  pt.id AS tenant_id,
  pt.tenant_seq,
  e.emp_idx,
  'Nhân viên ' || pt.tenant_seq || '-' || e.emp_idx AS full_name,
  'PERF-' || pt.tenant_seq || '-' || lpad(e.emp_idx::text, 5, '0') AS employee_code,
  'perf.emp.' || pt.tenant_seq || '.' || e.emp_idx || '@perf.fams.local' AS email,
  sds.site_id,
  sds.shift_id
FROM perf_tenants pt
CROSS JOIN LATERAL generate_series(
  1,
  CASE WHEN pt.is_mega THEN (SELECT mega_employee_count FROM perf_params)
       ELSE (SELECT normal_employee_min FROM perf_params)
            + ((((hashtext(pt.id::text) % ((SELECT normal_employee_max FROM perf_params) - (SELECT normal_employee_min FROM perf_params)))
                + ((SELECT normal_employee_max FROM perf_params) - (SELECT normal_employee_min FROM perf_params)))
                % ((SELECT normal_employee_max FROM perf_params) - (SELECT normal_employee_min FROM perf_params))))
  END
) AS e(emp_idx)
-- Rải nhân viên đều qua các site của tenant (round-robin theo emp_idx modulo số site)
JOIN LATERAL (
  SELECT site_id, shift_id FROM perf_site_default_shift sds2
  WHERE sds2.tenant_seq = pt.tenant_seq
  ORDER BY sds2.site_idx
  OFFSET (e.emp_idx % (SELECT count(*) FROM perf_site_default_shift sds3 WHERE sds3.tenant_seq = pt.tenant_seq))
  LIMIT 1
) sds ON TRUE;

INSERT INTO employees (id, tenant_id, employee_code, first_name, last_name, email, position, status, hired_date, created_at, updated_at)
SELECT id, tenant_id, employee_code, 'NV', full_name, email, 'Nhân viên vận hành', 'active',
       (NOW() - ((tenant_seq + emp_idx) % 700 || ' days')::INTERVAL)::DATE, NOW(), NOW()
FROM perf_employees;

-- ── 7. Users cho ~40% nhân viên (đủ đạt khoảng 5.000-10.000 user tổng cộng) ──────
CREATE TEMP TABLE perf_employee_users AS
SELECT
  gen_random_uuid() AS user_id,
  pe.id AS employee_id,
  pe.email,
  pe.full_name
FROM perf_employees pe
WHERE (((hashtext(pe.id::text || 'hasuser') % 10) + 10) % 10) < 4;

INSERT INTO users (id, email, password_hash, display_name, is_active, email_verified, is_platform_admin, created_at, updated_at)
SELECT user_id, email, (SELECT password_hash FROM users WHERE email = 'admin@fams.com' LIMIT 1),
       full_name, TRUE, TRUE, FALSE, NOW() - '200 days'::INTERVAL, NOW()
FROM perf_employee_users
ON CONFLICT (email) DO NOTHING;

UPDATE employees e SET user_id = peu.user_id
FROM perf_employee_users peu
WHERE e.id = peu.employee_id AND e.user_id IS NULL;

INSERT INTO user_roles (id, user_id, role_id, tenant_id, created_at, updated_at)
SELECT gen_random_uuid(), peu.user_id, (SELECT id FROM roles WHERE tenant_id IS NULL AND name = 'EMPLOYEE'), pe.tenant_id,
       NOW(), NOW()
FROM perf_employee_users peu
JOIN perf_employees pe ON pe.id = peu.employee_id
ON CONFLICT (user_id, role_id, tenant_id) DO NOTHING;

-- ── 8. Assignments — 1/nhân viên, tại site/shift đã gán ở bước 6 ────────────────
CREATE TEMP TABLE perf_assignments AS
SELECT gen_random_uuid() AS id, pe.tenant_id, pe.site_id, pe.id AS employee_id, pe.shift_id, pe.tenant_seq, pe.emp_idx
FROM perf_employees pe;

INSERT INTO assignments (id, tenant_id, site_id, employee_id, shift_id, start_date, role, status, created_at, updated_at)
SELECT id, tenant_id, site_id, employee_id, shift_id,
       (NOW() - '400 days'::INTERVAL)::DATE, 'worker', 'active', NOW(), NOW()
FROM perf_assignments;

-- ── 9. Checkins — khối lượng chính của bộ dữ liệu hiệu năng ─────────────────────
-- Mỗi nhân viên: N ngày làm việc gần nhất (mega=90 ngày, thường=60 ngày), bỏ qua T7/CN,
-- giờ vào/ra lệch nhẹ quanh giờ ca (cùng công thức biến thiên đã dùng ở seed_historical.sql).
INSERT INTO checkins (
  id, tenant_id, site_id, employee_id, assignment_id, shift_id, status,
  check_in_at, check_in_lat, check_in_lon, check_in_accuracy, check_in_inside_geofence,
  check_out_at, check_out_lat, check_out_lon, check_out_accuracy, check_out_inside_geofence,
  work_minutes, gps_risk_score, created_at, updated_at
)
SELECT
  gen_random_uuid(), pa.tenant_id, pa.site_id, pa.employee_id, pa.id, pa.shift_id, 'valid',
  (wd.work_date + sh.start_time + (((((hashtext(pa.employee_id::text || wd.work_date::text) % 15) + 15) % 15)) || ' minutes')::INTERVAL)::TIMESTAMPTZ,
  ps.lat, ps.lon, 4.0, TRUE,
  (wd.work_date + sh.end_time - (((((hashtext(pa.employee_id::text || wd.work_date::text || 'co') % 10) + 10) % 10)) || ' minutes')::INTERVAL)::TIMESTAMPTZ,
  ps.lat, ps.lon, 4.0, TRUE,
  EXTRACT(EPOCH FROM (sh.end_time - sh.start_time))::INT / 60,
  0.05, wd.work_date, wd.work_date
FROM perf_assignments pa
JOIN perf_tenants pt ON pt.id = pa.tenant_id
JOIN perf_sites ps ON ps.id = pa.site_id
JOIN shifts sh ON sh.id = pa.shift_id
CROSS JOIN LATERAL generate_series(
  CURRENT_DATE - (CASE WHEN pt.is_mega THEN (SELECT mega_checkin_days FROM perf_params)
                        ELSE (SELECT normal_checkin_days FROM perf_params) END),
  CURRENT_DATE - 1,
  INTERVAL '1 day'
) AS wd(work_date)
WHERE EXTRACT(ISODOW FROM wd.work_date::DATE) BETWEEN 1 AND 5;

COMMIT;

-- ── Báo cáo số liệu thật đã sinh ra ──────────────────────────────────────────────
SELECT 'perf tenants'   AS metric, count(*) AS value FROM tenants   WHERE slug LIKE 'perf-tenant-%'
UNION ALL SELECT 'perf users',     count(*) FROM users     WHERE email LIKE 'perf.%@perf.fams.local'
UNION ALL SELECT 'perf sites',     count(*) FROM sites s JOIN tenants t ON t.id = s.tenant_id WHERE t.slug LIKE 'perf-tenant-%'
UNION ALL SELECT 'perf shifts',    count(*) FROM shifts sh JOIN tenants t ON t.id = sh.tenant_id WHERE t.slug LIKE 'perf-tenant-%'
UNION ALL SELECT 'perf employees', count(*) FROM employees e JOIN tenants t ON t.id = e.tenant_id WHERE t.slug LIKE 'perf-tenant-%'
UNION ALL SELECT 'perf assignments', count(*) FROM assignments a JOIN tenants t ON t.id = a.tenant_id WHERE t.slug LIKE 'perf-tenant-%'
UNION ALL SELECT 'perf checkins',  count(*) FROM checkins c JOIN tenants t ON t.id = c.tenant_id WHERE t.slug LIKE 'perf-tenant-%';
