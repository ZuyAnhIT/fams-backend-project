-- FAMS curated demo dataset v3.
-- Target: local development and staging only. Never execute against production.
-- All business timestamps use Asia/Ho_Chi_Minh and all history starts in September 2026.

BEGIN;
SELECT pg_advisory_xact_lock(hashtext('fams-curated-demo-seed-v3'));

CREATE OR REPLACE FUNCTION pg_temp.seed_uuid(seed_key TEXT)
RETURNS UUID
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT uuid_generate_v5(uuid_ns_url(), 'https://fams.local/demo/v3/' || seed_key)
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users WHERE email = 'admin@fams.com' AND deleted_at IS NULL) THEN
        RAISE EXCEPTION 'Platform Admin is missing. Run Flyway migrations before the demo seed.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM roles WHERE name = 'PLATFORM_ADMIN' AND tenant_id IS NULL) THEN
        RAISE EXCEPTION 'System roles are missing. Run Flyway migrations before the demo seed.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM plans WHERE name = 'enterprise' AND deleted_at IS NULL) THEN
        RAISE EXCEPTION 'SaaS plans are missing. Run Flyway migrations before the demo seed.';
    END IF;
END $$;

-- Archive the oversized v2 sample catalogue. Only exact, previously documented demo slugs
-- are touched; manually created/local customer tenants are preserved.
CREATE TEMP TABLE legacy_demo_tenant_ids (id UUID PRIMARY KEY) ON COMMIT DROP;
INSERT INTO legacy_demo_tenant_ids (id)
SELECT id
FROM tenants
WHERE slug IN (
    'acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc',
    'viet-phat-retail', 'hoang-gia-fnb', 'minh-chau-security', 'thanh-cong-real-estate',
    'viet-nam-cleaning', 'sao-mai-electric', 'phu-quy-mining', 'dai-duong-fishery',
    'tan-phat-agriculture', 'viet-tin-events', 'rong-vang-holdings',
    'hoa-phuong-trading', 'nam-viet-services'
);

CREATE TEMP TABLE legacy_demo_user_ids (id UUID PRIMARY KEY) ON COMMIT DROP;
INSERT INTO legacy_demo_user_ids (id)
SELECT owner_id FROM tenants
WHERE id IN (SELECT id FROM legacy_demo_tenant_ids) AND owner_id IS NOT NULL
ON CONFLICT DO NOTHING;
INSERT INTO legacy_demo_user_ids (id)
SELECT user_id FROM employees
WHERE tenant_id IN (SELECT id FROM legacy_demo_tenant_ids) AND user_id IS NOT NULL
ON CONFLICT DO NOTHING;
INSERT INTO legacy_demo_user_ids (id)
SELECT user_id FROM user_roles
WHERE tenant_id IN (SELECT id FROM legacy_demo_tenant_ids)
ON CONFLICT DO NOTHING;
INSERT INTO legacy_demo_user_ids (id)
SELECT id FROM users
WHERE email ~ '^(hotro|kythuat|kinhdoanh|vanhanh|baomat|billing|qlsp)[0-9]+[.]nentang@fams[.]com$'
   OR email IN (
       'chuaxacthucmail1@gmail.com', 'chuaxacthucmail2@gmail.com',
       'chuaxacthucphone1@gmail.com', 'chuaxacthucphone2@gmail.com',
       'taikhoanbikhoa@gmail.com', 'dangnhapgoogle@gmail.com',
       'bat2fa1@gmail.com', 'bat2fa2@gmail.com',
       'dung.pham.hr@gmail.com', 'truong.van.dat@gmail.com'
   )
ON CONFLICT DO NOTHING;

UPDATE refresh_tokens
SET revoked_at = COALESCE(revoked_at, NOW()), active_tenant_id = NULL
WHERE user_id IN (SELECT id FROM legacy_demo_user_ids)
   OR active_tenant_id IN (SELECT id FROM legacy_demo_tenant_ids);

UPDATE user_roles
SET deleted_at = COALESCE(deleted_at, NOW()), updated_at = NOW()
WHERE tenant_id IN (SELECT id FROM legacy_demo_tenant_ids)
   OR (tenant_id IS NULL AND user_id IN (SELECT id FROM legacy_demo_user_ids));

UPDATE tenants
SET owner_id = NULL,
    status = 'cancelled',
    deleted_at = COALESCE(deleted_at, NOW()),
    updated_at = NOW()
WHERE id IN (SELECT id FROM legacy_demo_tenant_ids);

UPDATE users u
SET is_active = FALSE,
    deleted_at = COALESCE(u.deleted_at, NOW()),
    updated_at = NOW()
WHERE u.id IN (SELECT id FROM legacy_demo_user_ids)
  AND u.is_platform_admin = FALSE
  AND NOT EXISTS (
      SELECT 1 FROM tenants t
      WHERE t.owner_id = u.id AND t.deleted_at IS NULL
  )
  AND NOT EXISTS (
      SELECT 1 FROM user_roles ur
      JOIN tenants t ON t.id = ur.tenant_id AND t.deleted_at IS NULL
      WHERE ur.user_id = u.id AND ur.deleted_at IS NULL
  );

-- Platform identities never own a company, never have employee profiles and never hold a
-- company-scoped role. Reconcile old local databases as well as fresh installations.
UPDATE users
SET is_active = TRUE,
    email_verified = TRUE,
    is_platform_admin = TRUE,
    password_hash = '$2b$10$fIjK7mXIYZH8RTqSyqI2pO2rV9y45S7JS9S5530ZtMBuuCHz3Hdv6',
    failed_login_attempts = 0,
    locked_until = NULL,
    deleted_at = NULL,
    updated_at = NOW()
WHERE email = 'admin@fams.com';

UPDATE tenants t
SET owner_id = NULL, updated_at = NOW()
FROM users u
WHERE t.owner_id = u.id AND u.is_platform_admin = TRUE;

UPDATE user_roles ur
SET deleted_at = COALESCE(ur.deleted_at, NOW()), updated_at = NOW()
FROM users u
WHERE ur.user_id = u.id
  AND u.is_platform_admin = TRUE
  AND ur.tenant_id IS NOT NULL
  AND ur.deleted_at IS NULL;

-- Every demo login is active, verified and has a real password hash copied from the local
-- Platform Admin fixture. The .test domain guarantees these are non-deliverable addresses.
WITH account(email, phone, display_name, date_of_birth, gender, address) AS (
    VALUES
      ('admin.anphat@fams.test',          '+84910000001', 'Nguyễn Hoàng Nam', '1985-04-12'::DATE, 'male',   'Ba Đình, Hà Nội'),
      ('hr.anphat@fams.test',             '+84910000002', 'Trần Thu Hà',      '1990-08-21'::DATE, 'female', 'Cầu Giấy, Hà Nội'),
      ('hr.support.anphat@fams.test',     '+84910000003', 'Lê Minh Anh',      '1992-02-15'::DATE, 'female', 'Nam Từ Liêm, Hà Nội'),
      ('supervisor.hq@fams.test',         '+84910000004', 'Phạm Quốc Huy',    '1988-11-03'::DATE, 'male',   'Hai Bà Trưng, Hà Nội'),
      ('supervisor.tayho@fams.test',      '+84910000005', 'Nguyễn Đức Long',  '1987-07-09'::DATE, 'male',   'Tây Hồ, Hà Nội'),
      ('supervisor.caugiay@fams.test',    '+84910000006', 'Võ Thị Mai',       '1991-05-26'::DATE, 'female', 'Cầu Giấy, Hà Nội'),
      ('supervisor.donganh@fams.test',    '+84910000007', 'Đỗ Thành Công',    '1986-01-18'::DATE, 'male',   'Đông Anh, Hà Nội'),
      ('duy.anh@fams.test',               '+84910000008', 'Nguyễn Bá Duy Anh','1999-10-19'::DATE, 'male',   'Thanh Xuân, Hà Nội'),
      ('minh.quan@fams.test',             '+84910000009', 'Nguyễn Minh Quân', '1998-06-10'::DATE, 'male',   'Bắc Từ Liêm, Hà Nội'),
      ('van.khoa@fams.test',              '+84910000010', 'Bùi Văn Khoa',     '1997-12-04'::DATE, 'male',   'Long Biên, Hà Nội'),
      ('thi.lan@fams.test',               '+84910000011', 'Phan Thị Lan',     '2000-03-14'::DATE, 'female', 'Hà Đông, Hà Nội'),
      ('gia.bao@fams.test',               '+84910000012', 'Hoàng Gia Bảo',    '1996-09-08'::DATE, 'male',   'Hoài Đức, Hà Nội'),
      ('ngoc.mai@fams.test',              '+84910000013', 'Trịnh Ngọc Mai',   '1999-01-27'::DATE, 'female', 'Đan Phượng, Hà Nội'),
      ('thanh.tung@fams.test',            '+84910000014', 'Vũ Thanh Tùng',    '1995-05-17'::DATE, 'male',   'Gia Lâm, Hà Nội'),
      ('thu.trang@fams.test',             '+84910000015', 'Đặng Thu Trang',   '2001-07-22'::DATE, 'female', 'Sóc Sơn, Hà Nội'),
      ('owner.minhlong@fams.test',        '+84910000016', 'Đinh Minh Long',   '1984-03-30'::DATE, 'male',   'Hải Phòng'),
      ('owner.saoviet@fams.test',         '+84910000017', 'Ngô Thanh Hương',  '1989-09-12'::DATE, 'female', 'Đà Nẵng')
), admin_hash AS (
    SELECT password_hash FROM users WHERE email = 'admin@fams.com'
)
INSERT INTO users (
    id, email, phone, password_hash, display_name,
    is_active, is_platform_admin, email_verified, phone_verified,
    date_of_birth, hometown, gender, address,
    failed_login_attempts, locked_until, created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('user:' || a.email), a.email, a.phone, h.password_hash, a.display_name,
       TRUE, FALSE, TRUE, TRUE,
       a.date_of_birth, 'Việt Nam', a.gender, a.address,
       0, NULL, '2026-09-01 00:00:00+07'::TIMESTAMPTZ, NOW(), NULL
FROM account a CROSS JOIN admin_hash h
ON CONFLICT (email) DO UPDATE SET
    phone = EXCLUDED.phone,
    password_hash = EXCLUDED.password_hash,
    display_name = EXCLUDED.display_name,
    is_active = TRUE,
    is_platform_admin = FALSE,
    email_verified = TRUE,
    phone_verified = TRUE,
    date_of_birth = EXCLUDED.date_of_birth,
    hometown = EXCLUDED.hometown,
    gender = EXCLUDED.gender,
    address = EXCLUDED.address,
    failed_login_attempts = 0,
    locked_until = NULL,
    deleted_at = NULL,
    updated_at = NOW();

-- Three companies: one complete construction tenant and two deliberately lightweight tenants.
WITH company(slug, name, domain, industry, owner_email, status) AS (
    VALUES
      ('demo-an-phat',   'Công ty CP Xây dựng An Phát', 'anphat.fams.test',   'Xây dựng',  'admin.anphat@fams.test',   'active'),
      ('demo-minh-long', 'Công ty TNHH Logistics Minh Long', 'minhlong.fams.test', 'Logistics', 'owner.minhlong@fams.test', 'active'),
      ('demo-sao-viet',  'Công ty TNHH Dịch vụ Sao Việt', 'saoviet.fams.test', 'Dịch vụ',   'owner.saoviet@fams.test',  'active')
)
INSERT INTO tenants (
    id, name, slug, domain, industry, country_code, timezone, locale,
    currency_code, status, owner_id, created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('tenant:' || c.slug), c.name, c.slug, c.domain, c.industry,
       'VN', 'Asia/Ho_Chi_Minh', 'vi-VN', 'VND', c.status, u.id,
       '2026-09-01 00:00:00+07'::TIMESTAMPTZ, NOW(), NULL
FROM company c JOIN users u ON u.email = c.owner_email
ON CONFLICT (slug) WHERE deleted_at IS NULL DO UPDATE SET
    name = EXCLUDED.name,
    domain = EXCLUDED.domain,
    industry = EXCLUDED.industry,
    country_code = 'VN',
    timezone = 'Asia/Ho_Chi_Minh',
    locale = 'vi-VN',
    currency_code = 'VND',
    status = EXCLUDED.status,
    owner_id = EXCLUDED.owner_id,
    deleted_at = NULL,
    updated_at = NOW();

WITH subscription(slug, plan_name, billing_cycle) AS (
    VALUES
      ('demo-an-phat', 'enterprise', 'MONTHLY'),
      ('demo-minh-long', 'starter', 'MONTHLY'),
      ('demo-sao-viet', 'starter', 'MONTHLY')
)
INSERT INTO tenant_subscriptions (
    id, tenant_id, plan_id, status, billing_cycle, started_at, expires_at, created_at, updated_at
)
SELECT pg_temp.seed_uuid('subscription:' || s.slug), t.id, p.id, 'ACTIVE', s.billing_cycle,
       '2026-09-01 00:00:00+07'::TIMESTAMPTZ,
       '2026-10-01 00:00:00+07'::TIMESTAMPTZ,
       '2026-09-01 00:00:00+07'::TIMESTAMPTZ, NOW()
FROM subscription s
JOIN tenants t ON t.slug = s.slug AND t.deleted_at IS NULL
JOIN plans p ON p.name = s.plan_name AND p.deleted_at IS NULL
ON CONFLICT (tenant_id) DO UPDATE SET
    plan_id = EXCLUDED.plan_id,
    status = 'ACTIVE',
    billing_cycle = EXCLUDED.billing_cycle,
    started_at = EXCLUDED.started_at,
    expires_at = EXCLUDED.expires_at,
    cancelled_at = NULL,
    updated_at = NOW();

INSERT INTO tenant_settings (
    id, tenant_id, date_format, time_format,
    brand_primary_color, brand_secondary_color, brand_accent_color,
    employee_code_prefix, employee_code_padding, employee_code_seq,
    data_retention_days, created_at, updated_at
)
SELECT pg_temp.seed_uuid('settings:' || t.slug), t.id, 'DD/MM/YYYY', 'HH:mm',
       CASE t.slug WHEN 'demo-an-phat' THEN '#1D4ED8' ELSE '#0F766E' END,
       '#FFFFFF', '#F59E0B',
       CASE t.slug WHEN 'demo-an-phat' THEN 'AP' WHEN 'demo-minh-long' THEN 'ML' ELSE 'SV' END,
       3, CASE t.slug WHEN 'demo-an-phat' THEN 15 ELSE 0 END,
       365, '2026-09-01 00:00:00+07'::TIMESTAMPTZ, NOW()
FROM tenants t
WHERE t.slug IN ('demo-an-phat', 'demo-minh-long', 'demo-sao-viet') AND t.deleted_at IS NULL
ON CONFLICT (tenant_id) DO UPDATE SET
    date_format = EXCLUDED.date_format,
    time_format = EXCLUDED.time_format,
    brand_primary_color = EXCLUDED.brand_primary_color,
    brand_secondary_color = EXCLUDED.brand_secondary_color,
    brand_accent_color = EXCLUDED.brand_accent_color,
    employee_code_prefix = EXCLUDED.employee_code_prefix,
    employee_code_padding = EXCLUDED.employee_code_padding,
    employee_code_seq = EXCLUDED.employee_code_seq,
    data_retention_days = EXCLUDED.data_retention_days,
    updated_at = NOW();

-- Five departments cover governance, HR, engineering, safety and site operations.
WITH department(code, name, description) AS (
    VALUES
      ('board',       'Ban Giám đốc',        'Điều hành và quản trị doanh nghiệp'),
      ('hr',          'Hành chính - Nhân sự','Nhân sự, chính sách và chấm công'),
      ('engineering', 'Kỹ thuật',            'Thiết kế và giám sát kỹ thuật'),
      ('safety',      'An toàn - Chất lượng','An toàn lao động và kiểm soát chất lượng'),
      ('operations',  'Thi công',            'Tổ chức thi công tại các công trình')
), primary_tenant AS (
    SELECT id FROM tenants WHERE slug = 'demo-an-phat' AND deleted_at IS NULL
), creator AS (
    SELECT id FROM users WHERE email = 'admin.anphat@fams.test'
)
INSERT INTO workspaces (
    id, tenant_id, name, description, type, status, created_by, created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('workspace:demo-an-phat:' || d.code), t.id, d.name, d.description,
       'department', 'active', c.id, '2026-09-01 00:00:00+07'::TIMESTAMPTZ, NOW(), NULL
FROM department d CROSS JOIN primary_tenant t CROSS JOIN creator c
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name, description = EXCLUDED.description, type = 'department',
    status = 'active', deleted_at = NULL, updated_at = NOW();

-- Four locations with realistic Hanoi coordinates and different check-in policies.
WITH site(code, name, description, address, latitude, longitude, checkin_policy, hide_polygon) AS (
    VALUES
      ('AP-HQ', 'Trụ sở Hà Nội', 'Văn phòng điều hành và nhân sự', '12 Duy Tân, Cầu Giấy, Hà Nội', 21.03030, 105.78210, 'gps_only', FALSE),
      ('AP-TH', 'Công trình Tây Hồ', 'Thi công tổ hợp dân dụng Tây Hồ', 'Võ Chí Công, Tây Hồ, Hà Nội', 21.07120, 105.80010, 'gps_face_liveness', TRUE),
      ('AP-CG', 'Công trình Cầu Giấy', 'Thi công tòa nhà văn phòng Cầu Giấy', 'Trần Thái Tông, Cầu Giấy, Hà Nội', 21.03380, 105.78840, 'gps_face', FALSE),
      ('AP-DA', 'Công trình Đông Anh', 'Thi công khu công nghiệp Đông Anh', 'Uy Nỗ, Đông Anh, Hà Nội', 21.13910, 105.84920, 'gps_only', FALSE)
), primary_tenant AS (
    SELECT id FROM tenants WHERE slug = 'demo-an-phat' AND deleted_at IS NULL
), creator AS (
    SELECT id FROM users WHERE email = 'admin.anphat@fams.test'
)
INSERT INTO sites (
    id, tenant_id, name, code, description, address, latitude, longitude,
    timezone, status, created_by, checkin_policy, hide_polygon_from_employee,
    created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('site:demo-an-phat:' || s.code), t.id, s.name, s.code,
       s.description, s.address, s.latitude, s.longitude,
       'Asia/Ho_Chi_Minh', 'active', c.id, s.checkin_policy, s.hide_polygon,
       '2026-09-01 00:00:00+07'::TIMESTAMPTZ, NOW(), NULL
FROM site s CROSS JOIN primary_tenant t CROSS JOIN creator c
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name, code = EXCLUDED.code, description = EXCLUDED.description,
    address = EXCLUDED.address, latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude,
    timezone = 'Asia/Ho_Chi_Minh', status = 'active',
    checkin_policy = EXCLUDED.checkin_policy,
    hide_polygon_from_employee = EXCLUDED.hide_polygon_from_employee,
    deleted_at = NULL, updated_at = NOW();

INSERT INTO geofences (
    id, site_id, tenant_id, coordinates, buffer_meters, status, created_by,
    area_sqm, change_reason, created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('geofence:' || s.code), s.id, s.tenant_id,
       format('[[%s,%s],[%s,%s],[%s,%s],[%s,%s],[%s,%s]]',
              s.longitude - 0.0010, s.latitude - 0.0010,
              s.longitude + 0.0010, s.latitude - 0.0010,
              s.longitude + 0.0010, s.latitude + 0.0010,
              s.longitude - 0.0010, s.latitude + 0.0010,
              s.longitude - 0.0010, s.latitude - 0.0010),
       30, 'active', u.id, 45000, 'Ranh giới mẫu đã được phê duyệt',
       '2026-09-01 00:00:00+07'::TIMESTAMPTZ, NOW(), NULL
FROM sites s
JOIN users u ON u.email = 'admin.anphat@fams.test'
JOIN tenants t ON t.id = s.tenant_id AND t.slug = 'demo-an-phat'
WHERE s.deleted_at IS NULL
ON CONFLICT (id) DO UPDATE SET
    coordinates = EXCLUDED.coordinates, buffer_meters = EXCLUDED.buffer_meters,
    status = 'active', area_sqm = EXCLUDED.area_sqm,
    change_reason = EXCLUDED.change_reason, deleted_at = NULL, updated_at = NOW();

-- Shift templates include office, split shifts and an overnight shift. Random-check policy
-- examples are deliberate: headquarters disabled, Tây Hồ inherited, Cầu Giấy custom config,
-- and Đông Anh automatic checks disabled while manual checks stay enabled.
WITH shift(site_code, shift_code, name, start_time, end_time, overnight, overtime,
           early_minutes, late_minutes, default_shift, check_policy,
           random_policy, manual_policy, max_ot_day, max_ot_week) AS (
    VALUES
      ('AP-HQ', 'office',    'Ca hành chính', '08:00'::TIME, '17:00'::TIME, FALSE, TRUE,  30, 120, TRUE,  NULL, 'disabled', 'enabled', 120, 360),
      ('AP-TH', 'morning',   'Ca sáng',       '06:00'::TIME, '14:00'::TIME, FALSE, FALSE, 20,  30, TRUE,  NULL, 'inherit',  'inherit', NULL, NULL),
      ('AP-TH', 'afternoon', 'Ca chiều',      '14:00'::TIME, '22:00'::TIME, FALSE, TRUE,  20, 120, FALSE, NULL, 'inherit',  'inherit', 120, 360),
      ('AP-TH', 'night',     'Ca đêm',        '22:00'::TIME, '06:00'::TIME, TRUE,  TRUE,  20, 120, FALSE, NULL, 'inherit',  'inherit', 120, 360),
      ('AP-CG', 'day',       'Ca công trường','07:00'::TIME, '16:00'::TIME, FALSE, TRUE,  30, 120, TRUE,  NULL, 'inherit',  'inherit', 120, 360),
      ('AP-DA', 'day',       'Ca công trường','07:30'::TIME, '17:30'::TIME, FALSE, TRUE,  30, 180, TRUE,  NULL, 'disabled', 'enabled', 180, 600)
), creator AS (
    SELECT id FROM users WHERE email = 'admin.anphat@fams.test'
)
INSERT INTO shifts (
    id, site_id, tenant_id, name, start_time, end_time, allow_overnight, status,
    created_by, allow_overtime, early_checkin_minutes, late_checkout_minutes,
    checkin_policy_override, max_ot_minutes_per_day, max_ot_minutes_per_week,
    is_default, grace_minutes, random_check_policy, manual_check_policy,
    created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('shift:' || sh.site_code || ':' || sh.shift_code), s.id, s.tenant_id,
       sh.name, sh.start_time, sh.end_time, sh.overnight, 'active', c.id,
       sh.overtime, sh.early_minutes, sh.late_minutes, sh.check_policy,
       sh.max_ot_day, sh.max_ot_week, sh.default_shift, 5,
       sh.random_policy, sh.manual_policy,
       '2026-09-01 00:00:00+07'::TIMESTAMPTZ, NOW(), NULL
FROM shift sh
JOIN sites s ON s.code = sh.site_code AND s.deleted_at IS NULL
JOIN tenants t ON t.id = s.tenant_id AND t.slug = 'demo-an-phat'
CROSS JOIN creator c
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name, start_time = EXCLUDED.start_time, end_time = EXCLUDED.end_time,
    allow_overnight = EXCLUDED.allow_overnight, status = 'active',
    allow_overtime = EXCLUDED.allow_overtime,
    early_checkin_minutes = EXCLUDED.early_checkin_minutes,
    late_checkout_minutes = EXCLUDED.late_checkout_minutes,
    checkin_policy_override = EXCLUDED.checkin_policy_override,
    max_ot_minutes_per_day = EXCLUDED.max_ot_minutes_per_day,
    max_ot_minutes_per_week = EXCLUDED.max_ot_minutes_per_week,
    is_default = EXCLUDED.is_default, grace_minutes = EXCLUDED.grace_minutes,
    random_check_policy = EXCLUDED.random_check_policy,
    manual_check_policy = EXCLUDED.manual_check_policy,
    deleted_at = NULL, updated_at = NOW();

-- Exactly 15 employee records. Every employee is linked to one verified login, one department
-- and one company role. Account roles and assignment roles are intentionally separate concepts.
WITH person(code, email, first_name, last_name, position, department_code, role_name, hired_date) AS (
    VALUES
      ('AP001','admin.anphat@fams.test',       'Nam',      'Nguyễn Hoàng','Giám đốc điều hành',        'board',       'TENANT_ADMIN',    '2020-01-06'::DATE),
      ('AP002','hr.anphat@fams.test',          'Hà',       'Trần Thu',    'Trưởng phòng Nhân sự',      'hr',          'HR_MANAGER',      '2021-03-15'::DATE),
      ('AP003','hr.support.anphat@fams.test',  'Anh',      'Lê Minh',     'Chuyên viên Nhân sự',        'hr',          'HR_MANAGER',      '2023-05-08'::DATE),
      ('AP004','supervisor.hq@fams.test',      'Huy',      'Phạm Quốc',   'Quản lý vận hành trụ sở',    'engineering', 'SITE_SUPERVISOR', '2021-08-02'::DATE),
      ('AP005','supervisor.tayho@fams.test',   'Long',     'Nguyễn Đức',  'Chỉ huy trưởng Tây Hồ',      'safety',      'SITE_SUPERVISOR', '2020-10-12'::DATE),
      ('AP006','supervisor.caugiay@fams.test', 'Mai',      'Võ Thị',      'Chỉ huy trưởng Cầu Giấy',    'operations',  'SITE_SUPERVISOR', '2022-01-10'::DATE),
      ('AP007','supervisor.donganh@fams.test', 'Công',     'Đỗ Thành',    'Chỉ huy trưởng Đông Anh',    'operations',  'SITE_SUPERVISOR', '2020-06-22'::DATE),
      ('AP008','duy.anh@fams.test',            'Duy Anh',  'Nguyễn Bá',   'Kỹ sư hiện trường',          'engineering', 'EMPLOYEE',        '2024-02-01'::DATE),
      ('AP009','minh.quan@fams.test',          'Minh Quân','Nguyễn',     'Kỹ thuật viên',               'engineering', 'EMPLOYEE',        '2024-04-15'::DATE),
      ('AP010','van.khoa@fams.test',           'Văn Khoa', 'Bùi',        'Thợ điện',                    'engineering', 'EMPLOYEE',        '2023-11-06'::DATE),
      ('AP011','thi.lan@fams.test',            'Thị Lan',  'Phan',       'Cán bộ an toàn',              'safety',      'EMPLOYEE',        '2023-09-18'::DATE),
      ('AP012','gia.bao@fams.test',            'Gia Bảo',  'Hoàng',      'Kỹ sư kết cấu',               'safety',      'EMPLOYEE',        '2022-07-11'::DATE),
      ('AP013','ngoc.mai@fams.test',           'Ngọc Mai', 'Trịnh',      'Nhân viên QA/QC',             'operations',  'EMPLOYEE',        '2024-01-22'::DATE),
      ('AP014','thanh.tung@fams.test',         'Thanh Tùng','Vũ',        'Tổ trưởng thi công',           'operations',  'EMPLOYEE',        '2022-10-03'::DATE),
      ('AP015','thu.trang@fams.test',          'Thu Trang','Đặng',       'Nhân viên hồ sơ công trường',  'operations',  'EMPLOYEE',        '2024-06-03'::DATE)
), primary_tenant AS (
    SELECT id FROM tenants WHERE slug = 'demo-an-phat' AND deleted_at IS NULL
)
INSERT INTO employees (
    id, tenant_id, user_id, employee_code, first_name, last_name, email, phone,
    position, department, department_id, status, hired_date, planned_role_id,
    created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('employee:demo-an-phat:' || p.code), t.id, u.id, p.code,
       p.first_name, p.last_name, p.email, u.phone, p.position, w.name, w.id,
       'active', p.hired_date, r.id,
       '2026-09-01 00:00:00+07'::TIMESTAMPTZ, NOW(), NULL
FROM person p CROSS JOIN primary_tenant t
JOIN users u ON u.email = p.email
JOIN workspaces w ON w.id = pg_temp.seed_uuid('workspace:demo-an-phat:' || p.department_code)
JOIN roles r ON r.name = p.role_name AND r.tenant_id IS NULL
ON CONFLICT (id) DO UPDATE SET
    user_id = EXCLUDED.user_id, employee_code = EXCLUDED.employee_code,
    first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name,
    email = EXCLUDED.email, phone = EXCLUDED.phone, position = EXCLUDED.position,
    department = EXCLUDED.department, department_id = EXCLUDED.department_id,
    status = 'active', hired_date = EXCLUDED.hired_date,
    planned_role_id = EXCLUDED.planned_role_id, terminated_at = NULL,
    deleted_at = NULL, updated_at = NOW();

-- Remove stale company memberships for reserved demo accounts, then restore exactly one role.
UPDATE user_roles ur
SET deleted_at = COALESCE(ur.deleted_at, NOW()), updated_at = NOW()
FROM users u
WHERE ur.user_id = u.id
  AND u.email LIKE '%@fams.test'
  AND ur.tenant_id IS NOT NULL
  AND ur.deleted_at IS NULL;

WITH membership(email, tenant_slug, role_name) AS (
    VALUES
      ('admin.anphat@fams.test',          'demo-an-phat',   'TENANT_ADMIN'),
      ('hr.anphat@fams.test',             'demo-an-phat',   'HR_MANAGER'),
      ('hr.support.anphat@fams.test',     'demo-an-phat',   'HR_MANAGER'),
      ('supervisor.hq@fams.test',         'demo-an-phat',   'SITE_SUPERVISOR'),
      ('supervisor.tayho@fams.test',      'demo-an-phat',   'SITE_SUPERVISOR'),
      ('supervisor.caugiay@fams.test',    'demo-an-phat',   'SITE_SUPERVISOR'),
      ('supervisor.donganh@fams.test',    'demo-an-phat',   'SITE_SUPERVISOR'),
      ('duy.anh@fams.test',               'demo-an-phat',   'EMPLOYEE'),
      ('minh.quan@fams.test',             'demo-an-phat',   'EMPLOYEE'),
      ('van.khoa@fams.test',              'demo-an-phat',   'EMPLOYEE'),
      ('thi.lan@fams.test',               'demo-an-phat',   'EMPLOYEE'),
      ('gia.bao@fams.test',               'demo-an-phat',   'EMPLOYEE'),
      ('ngoc.mai@fams.test',              'demo-an-phat',   'EMPLOYEE'),
      ('thanh.tung@fams.test',            'demo-an-phat',   'EMPLOYEE'),
      ('thu.trang@fams.test',             'demo-an-phat',   'EMPLOYEE'),
      ('owner.minhlong@fams.test',        'demo-minh-long', 'TENANT_ADMIN'),
      ('owner.saoviet@fams.test',         'demo-sao-viet',  'TENANT_ADMIN')
)
INSERT INTO user_roles (
    id, user_id, role_id, tenant_id, assigned_by, created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('user-role:' || m.email || ':' || m.tenant_slug || ':' || m.role_name),
       u.id, r.id, t.id, owner_user.id,
       '2026-09-01 00:00:00+07'::TIMESTAMPTZ, NOW(), NULL
FROM membership m
JOIN users u ON u.email = m.email
JOIN roles r ON r.name = m.role_name AND r.tenant_id IS NULL
JOIN tenants t ON t.slug = m.tenant_slug AND t.deleted_at IS NULL
JOIN users owner_user ON owner_user.id = t.owner_id
ON CONFLICT (user_id, role_id, tenant_id) DO UPDATE SET
    assigned_by = EXCLUDED.assigned_by, deleted_at = NULL, updated_at = NOW();

-- Each Site Supervisor is constrained to exactly the site they manage.
DELETE FROM user_role_sites urs
USING user_roles ur, users u, tenants t
WHERE urs.user_role_id = ur.id
  AND ur.user_id = u.id
  AND ur.tenant_id = t.id
  AND t.slug = 'demo-an-phat'
  AND u.email LIKE 'supervisor.%@fams.test';

WITH scope(email, site_code) AS (
    VALUES
      ('supervisor.hq@fams.test',      'AP-HQ'),
      ('supervisor.tayho@fams.test',   'AP-TH'),
      ('supervisor.caugiay@fams.test', 'AP-CG'),
      ('supervisor.donganh@fams.test', 'AP-DA')
)
INSERT INTO user_role_sites (user_role_id, site_id)
SELECT ur.id, s.id
FROM scope sc
JOIN users u ON u.email = sc.email
JOIN roles r ON r.name = 'SITE_SUPERVISOR' AND r.tenant_id IS NULL
JOIN tenants t ON t.slug = 'demo-an-phat' AND t.deleted_at IS NULL
JOIN user_roles ur ON ur.user_id = u.id AND ur.role_id = r.id
                  AND ur.tenant_id = t.id AND ur.deleted_at IS NULL
JOIN sites s ON s.tenant_id = t.id AND s.code = sc.site_code AND s.deleted_at IS NULL
ON CONFLICT DO NOTHING;

-- One primary department per employee. Department manager/lead labels mirror business duties.
INSERT INTO workspace_members (
    id, workspace_id, employee_id, tenant_id, role, assigned_by,
    is_primary, effective_from, created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('workspace-member:' || e.employee_code), e.department_id, e.id, e.tenant_id,
       CASE
         WHEN e.employee_code IN ('AP001','AP002') THEN 'manager'
         WHEN e.employee_code IN ('AP004','AP005','AP006','AP007') THEN 'lead'
         ELSE 'member'
       END,
       admin_user.id, TRUE, '2026-09-01'::DATE,
       '2026-09-01 00:00:00+07'::TIMESTAMPTZ, NOW(), NULL
FROM employees e
JOIN tenants t ON t.id = e.tenant_id AND t.slug = 'demo-an-phat'
JOIN users admin_user ON admin_user.email = 'admin.anphat@fams.test'
WHERE e.deleted_at IS NULL
ON CONFLICT (id) DO UPDATE SET
    workspace_id = EXCLUDED.workspace_id, employee_id = EXCLUDED.employee_id,
    tenant_id = EXCLUDED.tenant_id, role = EXCLUDED.role,
    assigned_by = EXCLUDED.assigned_by, is_primary = TRUE,
    effective_from = EXCLUDED.effective_from, left_at = NULL,
    deleted_at = NULL, updated_at = NOW();

-- Twelve operational people have one coherent site/shift assignment. Company Admin and HR
-- remain unassigned because they manage attendance rather than performing field check-ins.
WITH roster(employee_code, site_code, shift_name, assignment_role) AS (
    VALUES
      ('AP004','AP-HQ','Ca hành chính','supervisor'),
      ('AP005','AP-TH','Ca sáng','supervisor'),
      ('AP006','AP-CG','Ca công trường','supervisor'),
      ('AP007','AP-DA','Ca công trường','supervisor'),
      ('AP008','AP-TH','Ca sáng','worker'),
      ('AP009','AP-TH','Ca chiều','worker'),
      ('AP010','AP-TH','Ca đêm','worker'),
      ('AP011','AP-TH','Ca sáng','worker'),
      ('AP012','AP-CG','Ca công trường','worker'),
      ('AP013','AP-CG','Ca công trường','worker'),
      ('AP014','AP-DA','Ca công trường','worker'),
      ('AP015','AP-DA','Ca công trường','worker')
)
INSERT INTO assignments (
    id, tenant_id, site_id, employee_id, shift_id, start_date, end_date,
    role, status, notes, created_by, days_of_week, created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('assignment:' || r.employee_code), e.tenant_id, s.id, e.id, sh.id,
       '2026-09-01'::DATE, '2026-12-31'::DATE, r.assignment_role, 'active',
       'Phân công mẫu từ tháng 09/2026 (áp dụng tất cả các ngày)', admin_user.id, 127::SMALLINT,
       '2026-09-01 00:00:00+07'::TIMESTAMPTZ, NOW(), NULL
FROM roster r
JOIN employees e ON e.employee_code = r.employee_code AND e.deleted_at IS NULL
JOIN tenants t ON t.id = e.tenant_id AND t.slug = 'demo-an-phat'
JOIN sites s ON s.tenant_id = t.id AND s.code = r.site_code AND s.deleted_at IS NULL
JOIN shifts sh ON sh.site_id = s.id AND sh.name = r.shift_name AND sh.deleted_at IS NULL
JOIN users admin_user ON admin_user.email = 'admin.anphat@fams.test'
ON CONFLICT (id) DO UPDATE SET
    site_id = EXCLUDED.site_id, employee_id = EXCLUDED.employee_id,
    shift_id = EXCLUDED.shift_id, start_date = EXCLUDED.start_date,
    end_date = EXCLUDED.end_date, role = EXCLUDED.role, status = 'active',
    notes = EXCLUDED.notes, days_of_week = EXCLUDED.days_of_week,
    cancelled_by = NULL, cancelled_at = NULL, deleted_at = NULL, updated_at = NOW();

-- Random-check policy examples: company default, disabled headquarters override and a custom
-- window for Cầu Giấy. Đông Anh demonstrates per-shift disabling without cloning a config.
WITH config(config_key, site_code, checks, interval_minutes, start_time, end_time,
            mode, roles, response_seconds, active, window_mode, manual_allowed) AS (
    VALUES
      ('default', NULL,    2, 90, '08:00'::TIME, '17:00'::TIME, 'location_face_liveness', 'worker,supervisor', 180, TRUE,  'full_shift',    TRUE),
      ('hq',      'AP-HQ', 1, 60, '08:00'::TIME, '17:00'::TIME, 'location_only',          'worker,supervisor', 180, FALSE, 'full_shift',    TRUE),
      ('caugiay', 'AP-CG', 2, 90, '08:00'::TIME, '15:00'::TIME, 'location_face',          'worker,supervisor', 180, TRUE,  'custom_window', TRUE)
), primary_tenant AS (
    SELECT id FROM tenants WHERE slug = 'demo-an-phat' AND deleted_at IS NULL
), creator AS (
    SELECT id FROM users WHERE email = 'admin.anphat@fams.test'
)
INSERT INTO random_check_configs (
    id, tenant_id, site_id, checks_per_shift, min_interval_minutes,
    allowed_start_time, allowed_end_time, check_mode, applicable_roles,
    response_window_seconds, is_active, created_by, failure_escalation_threshold,
    window_mode, manual_checks_allowed, created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('random-config:' || c.config_key), t.id, s.id, c.checks,
       c.interval_minutes, c.start_time, c.end_time, c.mode, c.roles,
       c.response_seconds, c.active, u.id, 2, c.window_mode, c.manual_allowed,
       '2026-09-01 00:00:00+07'::TIMESTAMPTZ, NOW(), NULL
FROM config c CROSS JOIN primary_tenant t CROSS JOIN creator u
LEFT JOIN sites s ON s.tenant_id = t.id AND s.code = c.site_code AND s.deleted_at IS NULL
ON CONFLICT (id) DO UPDATE SET
    site_id = EXCLUDED.site_id, checks_per_shift = EXCLUDED.checks_per_shift,
    min_interval_minutes = EXCLUDED.min_interval_minutes,
    allowed_start_time = EXCLUDED.allowed_start_time,
    allowed_end_time = EXCLUDED.allowed_end_time,
    check_mode = EXCLUDED.check_mode, applicable_roles = EXCLUDED.applicable_roles,
    response_window_seconds = EXCLUDED.response_window_seconds,
    is_active = EXCLUDED.is_active, failure_escalation_threshold = EXCLUDED.failure_escalation_threshold,
    window_mode = EXCLUDED.window_mode, manual_checks_allowed = EXCLUDED.manual_checks_allowed,
    deleted_at = NULL, updated_at = NOW();

-- Operational accounts get enrolled demo Face ID profiles so face-required sites are usable.
INSERT INTO face_profiles (
    id, tenant_id, employee_id, consent_given, consent_given_at, status,
    embedding, enrolled_at, embedding_deleted, review_status,
    consent_version, consent_ip, consent_device, quality_score,
    created_at, updated_at
)
SELECT pg_temp.seed_uuid('face-profile:' || e.employee_code), e.tenant_id, e.id,
       TRUE, '2026-09-01 08:00:00+07'::TIMESTAMPTZ, 'enrolled',
       ARRAY[0.11, 0.22, 0.33, 0.44]::DOUBLE PRECISION[],
       '2026-09-01 08:05:00+07'::TIMESTAMPTZ, FALSE, 'none',
       '2026.09', '127.0.0.1', 'FAMS demo seed', 0.92,
       '2026-09-01 08:05:00+07'::TIMESTAMPTZ, NOW()
FROM employees e
JOIN tenants t ON t.id = e.tenant_id AND t.slug = 'demo-an-phat'
WHERE e.employee_code BETWEEN 'AP004' AND 'AP015' AND e.deleted_at IS NULL
ON CONFLICT (tenant_id, employee_id) DO UPDATE SET
    consent_given = TRUE, consent_given_at = EXCLUDED.consent_given_at,
    status = 'enrolled', embedding = EXCLUDED.embedding,
    enrolled_at = EXCLUDED.enrolled_at, revoked_at = NULL,
    embedding_deleted = FALSE, review_status = 'none',
    consent_version = EXCLUDED.consent_version,
    consent_ip = EXCLUDED.consent_ip, consent_device = EXCLUDED.consent_device,
    quality_score = EXCLUDED.quality_score, updated_at = NOW();

-- Attendance history covers 01–04 September 2026. It contains normal, late, early-leave,
-- overtime and logically closed missing-checkout examples without leaving a stale open session.
WITH workday AS (
    SELECT d::DATE AS attendance_date
    FROM generate_series('2026-09-01'::DATE, '2026-09-04'::DATE, INTERVAL '1 day') d
), base AS (
    SELECT a.*, e.employee_code, s.latitude, s.longitude,
           sh.start_time, sh.end_time, sh.allow_overnight, sh.allow_overtime,
           sh.late_checkout_minutes, sh.max_ot_minutes_per_day,
           sh.max_ot_minutes_per_week, sh.grace_minutes,
           wd.attendance_date,
           ((wd.attendance_date + sh.start_time) AT TIME ZONE 'Asia/Ho_Chi_Minh') AS planned_start,
           (((wd.attendance_date + sh.end_time)
              + CASE WHEN sh.allow_overnight THEN INTERVAL '1 day' ELSE INTERVAL '0 day' END)
              AT TIME ZONE 'Asia/Ho_Chi_Minh') AS planned_end
    FROM assignments a
    JOIN employees e ON e.id = a.employee_id AND e.deleted_at IS NULL
    JOIN tenants t ON t.id = a.tenant_id AND t.slug = 'demo-an-phat'
    JOIN sites s ON s.id = a.site_id AND s.deleted_at IS NULL
    JOIN shifts sh ON sh.id = a.shift_id AND sh.deleted_at IS NULL
    CROSS JOIN workday wd
    WHERE a.status = 'active' AND a.deleted_at IS NULL
), scenario AS (
    SELECT b.*,
           CASE WHEN employee_code = 'AP010' AND attendance_date = '2026-09-02'
                THEN INTERVAL '12 minutes' ELSE INTERVAL '0 minutes' END AS checkin_delta,
           CASE
             WHEN employee_code = 'AP012' AND attendance_date = '2026-09-03' THEN INTERVAL '-20 minutes'
             WHEN employee_code = 'AP014' AND attendance_date = '2026-09-04' THEN INTERVAL '30 minutes'
             ELSE INTERVAL '0 minutes'
           END AS checkout_delta,
           (employee_code = 'AP015' AND attendance_date = '2026-09-04') AS missing_checkout
    FROM base b
)
INSERT INTO checkins (
    id, tenant_id, site_id, employee_id, assignment_id, shift_id, status,
    check_in_at, check_in_lat, check_in_lon, check_in_accuracy, check_in_inside_geofence,
    check_out_at, check_out_lat, check_out_lon, check_out_accuracy, check_out_inside_geofence,
    work_minutes, gps_risk_score, device_id, note,
    face_verified, liveness_verified, face_verify_score,
    shift_start_time, shift_end_time, shift_allow_overnight, shift_allow_overtime,
    shift_late_checkout_minutes, effective_checkin_policy, source,
    shift_max_ot_minutes_per_day, shift_max_ot_minutes_per_week, shift_grace_minutes,
    session_closed_at, session_close_reason, session_expires_at,
    created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('checkin:' || employee_code || ':' || attendance_date),
       tenant_id, site_id, employee_id, id, shift_id, 'valid',
       planned_start + checkin_delta, latitude + 0.0001, longitude + 0.0001, 8, TRUE,
       CASE WHEN missing_checkout THEN NULL ELSE planned_end + checkout_delta END,
       CASE WHEN missing_checkout THEN NULL ELSE latitude + 0.0001 END,
       CASE WHEN missing_checkout THEN NULL ELSE longitude + 0.0001 END,
       CASE WHEN missing_checkout THEN NULL ELSE 7 END,
       CASE WHEN missing_checkout THEN NULL ELSE TRUE END,
       CASE WHEN missing_checkout THEN NULL
            ELSE GREATEST(0, EXTRACT(EPOCH FROM ((planned_end + checkout_delta) - (planned_start + checkin_delta))) / 60)::INTEGER END,
       0.02, 'demo-device-' || lower(employee_code), 'Dữ liệu chấm công mẫu tháng 09/2026',
       TRUE, TRUE, 0.93,
       start_time, end_time, allow_overnight, allow_overtime,
       late_checkout_minutes,
       (SELECT checkin_policy FROM sites site_policy WHERE site_policy.id = site_id),
       'online', max_ot_minutes_per_day, max_ot_minutes_per_week, grace_minutes,
       CASE WHEN missing_checkout THEN planned_end + make_interval(mins => late_checkout_minutes)
            ELSE planned_end + checkout_delta END,
       CASE WHEN missing_checkout THEN 'missing_checkout' ELSE 'checkout' END,
       planned_end + make_interval(mins => late_checkout_minutes),
       planned_start + checkin_delta, NOW(), NULL
FROM scenario
ON CONFLICT (id) DO UPDATE SET
    status = EXCLUDED.status, check_in_at = EXCLUDED.check_in_at,
    check_out_at = EXCLUDED.check_out_at, work_minutes = EXCLUDED.work_minutes,
    session_closed_at = EXCLUDED.session_closed_at,
    session_close_reason = EXCLUDED.session_close_reason,
    session_expires_at = EXCLUDED.session_expires_at,
    deleted_at = NULL, updated_at = NOW();

INSERT INTO attendance_summaries (
    id, tenant_id, employee_id, site_id, shift_id, assignment_id, attendance_date,
    first_checkin_at, last_checkout_at, total_work_minutes, session_count, status,
    is_late, late_minutes, is_early_leave, early_leave_minutes, ot_minutes,
    missing_checkout, has_pending_review_session, has_rejected_session,
    has_random_check_failure, ot_daily_limit_exceeded, ot_weekly_limit_exceeded,
    created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('attendance:' || e.employee_code || ':' || c.check_in_at::DATE),
       c.tenant_id, c.employee_id, c.site_id, c.shift_id, c.assignment_id,
       (c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE,
       c.check_in_at, c.check_out_at, COALESCE(c.work_minutes, 0), 1,
       CASE WHEN c.check_out_at IS NULL THEN 'incomplete' ELSE 'present' END,
       (e.employee_code = 'AP010' AND (c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE = '2026-09-02'),
       CASE WHEN e.employee_code = 'AP010' AND (c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE = '2026-09-02' THEN 7 ELSE 0 END,
       (e.employee_code = 'AP012' AND (c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE = '2026-09-03'),
       CASE WHEN e.employee_code = 'AP012' AND (c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE = '2026-09-03' THEN 20 ELSE 0 END,
       CASE WHEN e.employee_code = 'AP014' AND (c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE = '2026-09-04' THEN 30 ELSE 0 END,
       c.check_out_at IS NULL, FALSE, FALSE, FALSE, FALSE, FALSE,
       c.created_at, NOW(), NULL
FROM checkins c
JOIN employees e ON e.id = c.employee_id
JOIN tenants t ON t.id = c.tenant_id AND t.slug = 'demo-an-phat'
WHERE c.deleted_at IS NULL
  AND (c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE BETWEEN '2026-09-01' AND '2026-09-04'
ON CONFLICT (tenant_id, employee_id, site_id, attendance_date) DO UPDATE SET
    shift_id = EXCLUDED.shift_id, assignment_id = EXCLUDED.assignment_id,
    first_checkin_at = EXCLUDED.first_checkin_at,
    last_checkout_at = EXCLUDED.last_checkout_at,
    total_work_minutes = EXCLUDED.total_work_minutes,
    session_count = EXCLUDED.session_count, status = EXCLUDED.status,
    is_late = EXCLUDED.is_late, late_minutes = EXCLUDED.late_minutes,
    is_early_leave = EXCLUDED.is_early_leave,
    early_leave_minutes = EXCLUDED.early_leave_minutes,
    ot_minutes = EXCLUDED.ot_minutes, missing_checkout = EXCLUDED.missing_checkout,
    deleted_at = NULL, updated_at = NOW();

-- Three closed random-check scenarios for reporting: pass, no response and location failure.
WITH sample(employee_code, check_index, status, scheduled_at, outcome) AS (
    VALUES
      ('AP008', 1, 'responded',   '2026-09-03 09:30:00+07'::TIMESTAMPTZ, 'pass'),
      ('AP009', 1, 'no_response', '2026-09-03 18:00:00+07'::TIMESTAMPTZ, NULL),
      ('AP013', 1, 'responded',   '2026-09-03 10:00:00+07'::TIMESTAMPTZ, 'fail')
), tenant_config AS (
    SELECT id FROM random_check_configs
    WHERE tenant_id = (SELECT id FROM tenants WHERE slug = 'demo-an-phat' AND deleted_at IS NULL)
      AND site_id IS NULL AND deleted_at IS NULL
)
INSERT INTO scheduled_checks (
    id, tenant_id, assignment_id, employee_id, site_id, shift_id, config_id,
    config_snapshot, check_date, check_index, scheduled_at, expires_at, status,
    sent_at, created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('scheduled-check:' || sm.employee_code || ':2026-09-03:' || sm.check_index),
       a.tenant_id, a.id, a.employee_id, a.site_id, a.shift_id, cfg.id,
       jsonb_build_object('checkMode','location_face_liveness','checksPerShift',2,
                          'minIntervalMinutes',90,'windowMode','full_shift',
                          'responseWindowSeconds',180),
       '2026-09-03'::DATE, sm.check_index, sm.scheduled_at,
       sm.scheduled_at + INTERVAL '3 minutes', sm.status,
       sm.scheduled_at, sm.scheduled_at - INTERVAL '5 minutes', NOW(), NULL
FROM sample sm
JOIN employees e ON e.employee_code = sm.employee_code AND e.deleted_at IS NULL
JOIN tenants t ON t.id = e.tenant_id AND t.slug = 'demo-an-phat'
JOIN assignments a ON a.employee_id = e.id AND a.status = 'active' AND a.deleted_at IS NULL
CROSS JOIN tenant_config cfg
ON CONFLICT (id) DO UPDATE SET
    status = EXCLUDED.status, scheduled_at = EXCLUDED.scheduled_at,
    expires_at = EXCLUDED.expires_at, sent_at = EXCLUDED.sent_at,
    deleted_at = NULL, updated_at = NOW();

WITH response(employee_code, outcome, verified, reason) AS (
    VALUES
      ('AP008', 'pass', TRUE,  NULL),
      ('AP013', 'fail', FALSE, 'Vị trí phản hồi nằm ngoài vùng chấm công')
)
INSERT INTO check_responses (
    id, tenant_id, scheduled_check_id, employee_id, responded_at,
    latitude, longitude, accuracy_meters, location_verified,
    face_verified, liveness_verified, outcome, failure_reason,
    face_verify_score, photo_submitted, created_at
)
SELECT pg_temp.seed_uuid('check-response:' || r.employee_code || ':2026-09-03'),
       e.tenant_id, sc.id, e.id, sc.scheduled_at + INTERVAL '1 minute',
       s.latitude + CASE WHEN r.verified THEN 0.0001 ELSE 0.0500 END,
       s.longitude + CASE WHEN r.verified THEN 0.0001 ELSE 0.0500 END,
       8, r.verified, TRUE, TRUE, r.outcome, r.reason, 0.91, TRUE,
       sc.scheduled_at + INTERVAL '1 minute'
FROM response r
JOIN employees e ON e.employee_code = r.employee_code
JOIN scheduled_checks sc ON sc.employee_id = e.id AND sc.check_date = '2026-09-03' AND sc.check_index = 1
JOIN sites s ON s.id = sc.site_id
ON CONFLICT (scheduled_check_id) DO UPDATE SET
    outcome = EXCLUDED.outcome, location_verified = EXCLUDED.location_verified,
    failure_reason = EXCLUDED.failure_reason, responded_at = EXCLUDED.responded_at;

INSERT INTO violations (
    id, tenant_id, employee_id, site_id, scheduled_check_id, check_response_id,
    violation_type, check_date, description, resolved, resolution,
    resolution_reason, affects_attendance, attendance_impact_reviewed,
    created_at, updated_at, deleted_at
)
SELECT pg_temp.seed_uuid('violation:' || e.employee_code || ':2026-09-03'),
       e.tenant_id, e.id, sc.site_id, sc.id, cr.id,
       CASE WHEN e.employee_code = 'AP009' THEN 'no_response' ELSE 'location_fail' END,
       '2026-09-03'::DATE,
       CASE WHEN e.employee_code = 'AP009'
            THEN 'Không phản hồi kiểm tra ngẫu nhiên trong thời hạn'
            ELSE 'Phản hồi kiểm tra nằm ngoài phạm vi công trình' END,
       FALSE, NULL, NULL, FALSE, FALSE, sc.expires_at, NOW(), NULL
FROM employees e
JOIN scheduled_checks sc ON sc.employee_id = e.id AND sc.check_date = '2026-09-03'
LEFT JOIN check_responses cr ON cr.scheduled_check_id = sc.id
WHERE e.employee_code IN ('AP009','AP013')
ON CONFLICT (id) DO UPDATE SET
    scheduled_check_id = EXCLUDED.scheduled_check_id,
    check_response_id = EXCLUDED.check_response_id,
    violation_type = EXCLUDED.violation_type,
    description = EXCLUDED.description, resolved = FALSE,
    resolution = NULL, resolution_reason = NULL,
    deleted_at = NULL, updated_at = NOW();

UPDATE attendance_summaries ats
SET has_random_check_failure = TRUE, updated_at = NOW()
FROM employees e
WHERE ats.employee_id = e.id
  AND e.employee_code IN ('AP009','AP013')
  AND ats.attendance_date = '2026-09-03';

-- A small notification/audit trail makes inbox and audit screens useful without flooding them.
INSERT INTO notifications (
    id, tenant_id, user_id, event_type, title, body, is_read, created_at, metadata, priority
)
SELECT pg_temp.seed_uuid('notification:' || u.email || ':welcome'), t.id, u.id,
       'assignment.created', 'Phân công tháng 09/2026',
       'Bạn đã được phân công vào lịch làm việc mẫu của Công ty CP Xây dựng An Phát.',
       (u.email = 'duy.anh@fams.test'), '2026-09-01 08:00:00+07'::TIMESTAMPTZ,
       jsonb_build_object('seedVersion','v3','businessStartDate','2026-09-01'), 'normal'
FROM users u
JOIN employees e ON e.user_id = u.id AND e.deleted_at IS NULL
JOIN tenants t ON t.id = e.tenant_id AND t.slug = 'demo-an-phat'
WHERE e.employee_code BETWEEN 'AP004' AND 'AP015'
ON CONFLICT (id) DO UPDATE SET
    title = EXCLUDED.title, body = EXCLUDED.body,
    metadata = EXCLUDED.metadata, priority = EXCLUDED.priority;

INSERT INTO audit_logs (
    id, tenant_id, actor_id, actor_email, entity_type, entity_id, action,
    new_value, request_id, ip_address, user_agent, created_at, endpoint, http_status
)
SELECT pg_temp.seed_uuid('audit:demo-an-phat:seed-v3'), t.id, u.id, u.email,
       'Tenant', t.id::TEXT, 'demo_seed_reconciled',
       jsonb_build_object('version','v3','employees',15,'sites',4,'startDate','2026-09-01'),
       'demo-seed-v3', '127.0.0.1', 'FAMS seed script', NOW(), 'scripts/seed.sh', 200
FROM tenants t JOIN users u ON u.email = 'admin.anphat@fams.test'
WHERE t.slug = 'demo-an-phat' AND t.deleted_at IS NULL
ON CONFLICT (id) DO UPDATE SET new_value = EXCLUDED.new_value, created_at = NOW();

COMMIT;
