-- ============================================================
-- FAMS Historical Seed Data (Vietnamese dataset)
-- ============================================================
-- Inserts realistic demo data for the past 30 days across the
-- five demo tenants: acme-corp, beta-industries, gamma-logistics,
-- tia-sang-startup, dong-a-jsc.
--
-- Tables populated:
--   face_profiles, checkins, attendance_summaries,
--   random_check_configs (site-level overrides),
--   scheduled_checks, check_responses, violations,
--   notifications, notification_templates, audit_logs,
--   tenant_settings, face_verify_requests, employee_invitations
--   (expired ones), user_notification_settings, user_devices,
--   notification_delivery_logs.
--
-- Also links two people to two tenants each under one shared
-- login (see "Multi-tenant person linking" below):
--   - dung.pham.hr@gmail.com: HR_MANAGER at Hoàng Long (acme-corp)
--     + SITE_SUPERVISOR at Bình Minh (beta-industries)
--   - truong.van.dat@gmail.com: EMPLOYEE at Phương Nam (gamma-logistics)
--     + EMPLOYEE at Tia Sáng (tia-sang-startup)
--   Both share the platform admin's password hash for demo login
--   convenience (Admin@1234).
--
-- scheduled_job_status is intentionally NOT seeded here — it is
-- populated automatically by the app's own @Scheduled jobs
-- (AttendanceSummaryJob, RandomCheckDispatchJob, etc.) the moment
-- the backend runs, so seeding it here would just fight real data.
--
-- Safe to run multiple times: all inserts use ON CONFLICT DO NOTHING
-- or an equivalent NOT EXISTS guard.
-- ============================================================

BEGIN;

-- ── Face Profiles ─────────────────────────────────────────────────────────────
-- One profile per employee; statuses vary for demo coverage.
-- Enrolled profiles get a short demo embedding vector (not a real face
-- encoding — just enough to exercise the embedding_deleted / revoke paths).

-- status (not_enrolled|enrolled|revoked) is the last-APPROVED state that gates checkin;
-- review_status (none|pending|rejected) tracks an independent in-flight submission — see
-- FaceIdService's class-level javadoc for the full state machine. Bucket 1 demos a first-time
-- submission awaiting HR review (status stays not_enrolled); bucket 3 demos a RE-enrollment
-- awaiting review on top of an already-approved face (status stays enrolled, old face still
-- usable) — both populate docs/api/face-id-management-api.md's "hàng đợi duyệt" (pending-review
-- queue) with realistic demo data instead of leaving it permanently empty.
INSERT INTO face_profiles (
    id, tenant_id, employee_id,
    consent_given, consent_given_at,
    status, embedding, embedding_deleted,
    review_status, pending_embedding, pending_photo_count, submitted_at,
    enrolled_at, revoked_at,
    created_at, updated_at
)
SELECT
    gen_random_uuid(),
    e.tenant_id,
    e.id,
    TRUE,
    NOW() - ((20 + (hashtext(e.id::text) % 20))::TEXT || ' days')::INTERVAL,
    CASE ((hashtext(e.id::text) % 10) + 10) % 10
        WHEN 0 THEN 'not_enrolled'
        WHEN 1 THEN 'not_enrolled'
        WHEN 2 THEN 'revoked'
        ELSE      'enrolled'
    END,
    CASE ((hashtext(e.id::text) % 10) + 10) % 10
        WHEN 0 THEN NULL
        WHEN 1 THEN NULL
        WHEN 2 THEN NULL
        ELSE ARRAY(
            SELECT round((((hashtext(e.id::text || i::text) % 200) - 100))::NUMERIC / 100.0, 4)::DOUBLE PRECISION
            FROM generate_series(1, 16) AS i
        )
    END,
    FALSE,
    CASE ((hashtext(e.id::text) % 10) + 10) % 10
        WHEN 1 THEN 'pending'
        WHEN 3 THEN 'pending'
        ELSE 'none'
    END,
    CASE ((hashtext(e.id::text) % 10) + 10) % 10
        WHEN 1 THEN ARRAY(
            SELECT round((((hashtext('pending:' || e.id::text || i::text) % 200) - 100))::NUMERIC / 100.0, 4)::DOUBLE PRECISION
            FROM generate_series(1, 16) AS i
        )
        WHEN 3 THEN ARRAY(
            SELECT round((((hashtext('pending:' || e.id::text || i::text) % 200) - 100))::NUMERIC / 100.0, 4)::DOUBLE PRECISION
            FROM generate_series(1, 16) AS i
        )
        ELSE NULL
    END,
    CASE ((hashtext(e.id::text) % 10) + 10) % 10
        WHEN 1 THEN 3 + (hashtext(e.id::text) % 3)
        WHEN 3 THEN 3 + (hashtext(e.id::text) % 3)
        ELSE NULL
    END,
    CASE ((hashtext(e.id::text) % 10) + 10) % 10
        WHEN 1 THEN NOW() - ((1 + (hashtext(e.id::text) % 3))::TEXT || ' days')::INTERVAL
        WHEN 3 THEN NOW() - ((1 + (hashtext(e.id::text) % 3))::TEXT || ' days')::INTERVAL
        ELSE NULL
    END,
    CASE ((hashtext(e.id::text) % 10) + 10) % 10
        WHEN 0 THEN NULL
        WHEN 1 THEN NULL
        WHEN 2 THEN NULL
        ELSE NOW() - ((5 + (hashtext(e.id::text) % 15))::TEXT || ' days')::INTERVAL
    END,
    CASE ((hashtext(e.id::text) % 10) + 10) % 10
        WHEN 2 THEN NOW() - '3 days'::INTERVAL
        ELSE NULL
    END,
    NOW() - ((20 + (hashtext(e.id::text) % 20))::TEXT || ' days')::INTERVAL,
    NOW()
FROM employees e
JOIN tenants t ON t.id = e.tenant_id
WHERE t.slug IN ('acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc')
  AND e.deleted_at IS NULL
ON CONFLICT (tenant_id, employee_id) DO NOTHING;

-- ── Site-level Random Check Config Overrides ──────────────────────────────────
-- Give a couple of sites stricter / face-based configs.

INSERT INTO random_check_configs (
    id, tenant_id, site_id,
    checks_per_shift, min_interval_minutes,
    allowed_start_time, allowed_end_time,
    check_mode, applicable_roles,
    response_window_seconds, is_active,
    created_by,
    created_at, updated_at
)
SELECT
    gen_random_uuid(),
    s.tenant_id,
    s.id,
    3,
    45,
    '07:00'::TIME,
    '22:00'::TIME,
    'location_face',
    'worker,supervisor',
    240,
    TRUE,
    (SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
    NOW() - '10 days'::INTERVAL,
    NOW()
FROM sites s
JOIN tenants t ON t.id = s.tenant_id
WHERE t.slug IN ('acme-corp', 'gamma-logistics')
  AND s.code IN ('HL-HN', 'PN-N')
  AND s.deleted_at IS NULL
ON CONFLICT DO NOTHING;

-- ── Geofences (one active boundary per site) ──────────────────────────────────
-- A simple ~330m-wide rectangle centered on each site's coordinates.
-- coordinates is stored as JSON text: an array of [longitude, latitude] pairs.

INSERT INTO geofences (
  id, site_id, tenant_id, coordinates, buffer_meters, status, created_by, created_at, updated_at
)
SELECT
  gen_random_uuid(),
  s.id,
  s.tenant_id,
  '[' ||
    '[' || (s.longitude - 0.0015) || ',' || (s.latitude - 0.0015) || '],' ||
    '[' || (s.longitude + 0.0015) || ',' || (s.latitude - 0.0015) || '],' ||
    '[' || (s.longitude + 0.0015) || ',' || (s.latitude + 0.0015) || '],' ||
    '[' || (s.longitude - 0.0015) || ',' || (s.latitude + 0.0015) || '],' ||
    '[' || (s.longitude - 0.0015) || ',' || (s.latitude - 0.0015) || ']' ||
  ']',
  80,
  'active',
  (SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
  NOW() - '30 days'::INTERVAL,
  NOW()
FROM sites s
JOIN tenants t ON t.id = s.tenant_id
WHERE t.slug IN ('acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc')
  AND s.deleted_at IS NULL
  AND s.latitude IS NOT NULL AND s.longitude IS NOT NULL
ON CONFLICT DO NOTHING;

-- ── Geofences (superseded — earlier, redrawn boundary for ~half the sites) ────
-- Demonstrates the versioning/history feature: an old geofence kept for audit
-- purposes after being replaced by the current active one above.

INSERT INTO geofences (
  id, site_id, tenant_id, coordinates, buffer_meters, status, created_by, created_at, updated_at
)
SELECT
  gen_random_uuid(),
  s.id,
  s.tenant_id,
  '[' ||
    '[' || (s.longitude - 0.0010) || ',' || (s.latitude - 0.0010) || '],' ||
    '[' || (s.longitude + 0.0010) || ',' || (s.latitude - 0.0010) || '],' ||
    '[' || (s.longitude + 0.0010) || ',' || (s.latitude + 0.0010) || '],' ||
    '[' || (s.longitude - 0.0010) || ',' || (s.latitude + 0.0010) || '],' ||
    '[' || (s.longitude - 0.0010) || ',' || (s.latitude - 0.0010) || ']' ||
  ']',
  50,
  'superseded',
  (SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
  NOW() - '90 days'::INTERVAL,
  NOW() - '30 days'::INTERVAL
FROM sites s
JOIN tenants t ON t.id = s.tenant_id
WHERE t.slug IN ('acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc')
  AND s.deleted_at IS NULL
  AND s.latitude IS NOT NULL AND s.longitude IS NOT NULL
  AND (hashtext(s.id::text || 'supersede') % 2) = 0
  AND NOT EXISTS (
    SELECT 1 FROM geofences g WHERE g.site_id = s.id AND g.status = 'superseded'
  );

-- ── Historical Checkins (past 30 weekdays) ────────────────────────────────────

WITH
  demo_tenants AS (
    SELECT id FROM tenants
    WHERE slug IN ('acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc')
      AND deleted_at IS NULL
  ),
  demo_assignments AS (
    SELECT
      a.id                                          AS assignment_id,
      a.tenant_id,
      a.site_id,
      a.employee_id,
      a.shift_id,
      a.start_date,
      a.end_date,
      sh.start_time,
      sh.end_time,
      sh.allow_overnight,
      COALESCE(s.latitude,  21.0285)                AS site_lat,
      COALESCE(s.longitude, 105.8542)               AS site_lon,
      EXTRACT(EPOCH FROM (
          CASE WHEN sh.allow_overnight
               THEN sh.end_time + INTERVAL '24 hours' - sh.start_time
               ELSE sh.end_time - sh.start_time
          END
      ))::INT / 60                                  AS planned_minutes
    FROM assignments a
    JOIN shifts     sh ON sh.id = a.shift_id      AND sh.deleted_at IS NULL
    JOIN sites       s ON  s.id = a.site_id       AND  s.deleted_at IS NULL
    JOIN employees   e ON  e.id = a.employee_id   AND  e.deleted_at IS NULL
                      AND e.status = 'active'
    WHERE a.status = 'active' AND a.deleted_at IS NULL
      AND a.tenant_id IN (SELECT id FROM demo_tenants)
  ),
  work_dates AS (
    SELECT d::DATE AS work_date
    FROM generate_series(
      CURRENT_DATE - INTERVAL '30 days',
      CURRENT_DATE - INTERVAL '1 day',
      INTERVAL '1 day'
    ) AS d
    WHERE EXTRACT(ISODOW FROM d::DATE) BETWEEN 1 AND 5
  ),
  raw_checkins AS (
    SELECT
      gen_random_uuid()                    AS id,
      da.tenant_id,
      da.site_id,
      da.employee_id,
      da.assignment_id,
      da.shift_id,
      -- Introduce occasional late check-ins and early check-outs
      CASE
        WHEN (hashtext(da.employee_id::text || wd.work_date::text)) % 7 = 0
          THEN 'pending_review'   -- ~14% flagged for review
        ELSE 'valid'
      END                                  AS status,
      -- Check-in time: shift start + 0-15 minute variation
      (wd.work_date + da.start_time
        + (((hashtext(da.employee_id::text || wd.work_date::text) % 15) + 15) % 15
           || ' minutes')::INTERVAL
      )::TIMESTAMPTZ                       AS check_in_at,
      -- Check-out time: shift end ± up to 20 minutes
      CASE WHEN da.allow_overnight THEN
        ((wd.work_date + INTERVAL '1 day')::DATE + da.end_time
          - (((hashtext(da.assignment_id::text || wd.work_date::text) % 20) + 20) % 20
             || ' minutes')::INTERVAL
        )::TIMESTAMPTZ
      ELSE
        (wd.work_date + da.end_time
          - (((hashtext(da.assignment_id::text || wd.work_date::text) % 20) + 20) % 20
             || ' minutes')::INTERVAL
        )::TIMESTAMPTZ
      END                                  AS check_out_at,
      -- GPS coordinates near site (vary by ±100 m)
      da.site_lat  + ((hashtext(da.employee_id::text || wd.work_date::text || 'lat')  % 100) - 50)::FLOAT / 100000.0  AS ci_lat,
      da.site_lon  + ((hashtext(da.employee_id::text || wd.work_date::text || 'lon')  % 100) - 50)::FLOAT / 100000.0  AS ci_lon,
      da.site_lat  + ((hashtext(da.employee_id::text || wd.work_date::text || 'lat2') % 100) - 50)::FLOAT / 100000.0  AS co_lat,
      da.site_lon  + ((hashtext(da.employee_id::text || wd.work_date::text || 'lon2') % 100) - 50)::FLOAT / 100000.0  AS co_lon,
      da.planned_minutes                   AS work_minutes,
      wd.work_date
    FROM demo_assignments da
    CROSS JOIN work_dates wd
    WHERE wd.work_date >= da.start_date
      AND (da.end_date IS NULL OR wd.work_date <= da.end_date)
  )
INSERT INTO checkins (
  id, tenant_id, site_id, employee_id, assignment_id, shift_id,
  status,
  check_in_at,  check_in_lat,  check_in_lon,  check_in_accuracy,  check_in_inside_geofence,
  check_out_at, check_out_lat, check_out_lon, check_out_accuracy, check_out_inside_geofence,
  work_minutes, gps_risk_score,
  face_verified, liveness_verified, face_verify_score,
  created_at, updated_at
)
SELECT
  id, tenant_id, site_id, employee_id, assignment_id, shift_id,
  status,
  check_in_at,  ci_lat, ci_lon, 3.5 + ((hashtext(id::text) % 50) / 10.0), TRUE,
  check_out_at, co_lat, co_lon, 4.0 + ((hashtext(id::text || 'co') % 40) / 10.0), TRUE,
  work_minutes,
  ((hashtext(id::text || 'gps') % 20) / 100.0),
  NULL, NULL, NULL,
  check_in_at, check_out_at
FROM raw_checkins rc
WHERE NOT EXISTS (
  SELECT 1 FROM checkins ex
  WHERE ex.employee_id   = rc.employee_id
    AND ex.assignment_id = rc.assignment_id
    AND ex.check_in_at::DATE = rc.work_date
    AND ex.deleted_at IS NULL
);

-- ── Attendance Summaries ──────────────────────────────────────────────────────

INSERT INTO attendance_summaries (
  id, tenant_id, employee_id, site_id, shift_id, assignment_id,
  attendance_date,
  first_checkin_at, last_checkout_at, total_work_minutes, session_count,
  status,
  is_late, late_minutes,
  is_early_leave, early_leave_minutes,
  ot_minutes, missing_checkout,
  adjustment_reason,
  created_at, updated_at
)
SELECT
  gen_random_uuid(),
  agg.tenant_id,
  agg.employee_id,
  agg.site_id,
  agg.shift_id,
  agg.assignment_id,
  agg.attendance_date,
  agg.first_checkin_at,
  agg.last_checkout_at,
  agg.total_work_minutes,
  agg.session_count,
  agg.status,
  -- is_late: first check-in more than 10 min after shift start
  (agg.first_checkin_at > (agg.attendance_date + sh.start_time + INTERVAL '10 minutes')),
  GREATEST(0,
    EXTRACT(EPOCH FROM (agg.first_checkin_at - (agg.attendance_date + sh.start_time)))::INT / 60
  ),
  FALSE,
  0,
  -- OT minutes: actual work beyond planned shift
  GREATEST(0,
    agg.total_work_minutes - (
      EXTRACT(EPOCH FROM (
        CASE WHEN sh.allow_overnight
             THEN sh.end_time + INTERVAL '24 hours' - sh.start_time
             ELSE sh.end_time - sh.start_time
        END
      ))::INT / 60
    )
  ),
  agg.missing_checkout,
  CASE WHEN agg.missing_checkout THEN 'HR đã liên hệ nhân viên xác nhận giờ ra sau khi phát hiện thiếu chấm công ra.' ELSE NULL END,
  NOW(), NOW()
FROM (
  SELECT
    c.tenant_id,
    c.employee_id,
    c.site_id,
    (array_agg(c.shift_id      ORDER BY c.check_in_at))[1] AS shift_id,
    (array_agg(c.assignment_id ORDER BY c.check_in_at))[1] AS assignment_id,
    c.check_in_at::DATE                                     AS attendance_date,
    MIN(c.check_in_at)                                      AS first_checkin_at,
    MAX(c.check_out_at)                                     AS last_checkout_at,
    COALESCE(SUM(c.work_minutes), 0)                        AS total_work_minutes,
    COUNT(*)                                                AS session_count,
    CASE WHEN MAX(c.check_out_at) IS NULL THEN 'incomplete' ELSE 'present' END AS status,
    (MAX(c.check_out_at) IS NULL)                           AS missing_checkout
  FROM checkins c
  WHERE c.deleted_at IS NULL
    AND c.tenant_id IN (
      SELECT id FROM tenants
      WHERE slug IN ('acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc')
        AND deleted_at IS NULL
    )
  GROUP BY c.tenant_id, c.employee_id, c.site_id, c.check_in_at::DATE
) agg
JOIN shifts sh ON sh.id = agg.shift_id AND sh.deleted_at IS NULL
ON CONFLICT (tenant_id, employee_id, site_id, attendance_date) DO NOTHING;

-- ── Scheduled Checks (past 14 days) ──────────────────────────────────────────

WITH
  demo_configs AS (
    SELECT rcc.id AS config_id, rcc.tenant_id,
           jsonb_build_object(
             'checksPerShift',       rcc.checks_per_shift,
             'minIntervalMinutes',   rcc.min_interval_minutes,
             'checkMode',            rcc.check_mode,
             'responseWindowSeconds',rcc.response_window_seconds
           ) AS snap
    FROM random_check_configs rcc
    JOIN tenants t ON t.id = rcc.tenant_id
    WHERE rcc.site_id IS NULL AND rcc.deleted_at IS NULL
      AND t.slug IN ('acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc')
  ),
  active_assignments AS (
    SELECT
      a.id        AS assignment_id,
      a.tenant_id,
      a.site_id,
      a.employee_id,
      a.shift_id,
      ROW_NUMBER() OVER (PARTITION BY a.tenant_id ORDER BY a.id) AS rn
    FROM assignments a
    JOIN employees e ON e.id = a.employee_id AND e.deleted_at IS NULL AND e.status = 'active'
    WHERE a.status = 'active' AND a.deleted_at IS NULL
      AND a.tenant_id IN (SELECT tenant_id FROM demo_configs)
  ),
  -- At most 4 assignments per tenant, 3 checks each over past 14 weekdays
  limited_assignments AS (
    SELECT * FROM active_assignments WHERE rn <= 4
  ),
  check_dates AS (
    SELECT d::DATE AS check_date
    FROM generate_series(
      CURRENT_DATE - INTERVAL '14 days',
      CURRENT_DATE - INTERVAL '1 day',
      INTERVAL '1 day'
    ) AS d
    WHERE EXTRACT(ISODOW FROM d::DATE) BETWEEN 1 AND 5
  )
INSERT INTO scheduled_checks (
  id, tenant_id, assignment_id, employee_id, site_id, shift_id,
  config_id, config_snapshot, check_date, check_index,
  scheduled_at, expires_at,
  status,
  created_at, updated_at
)
SELECT
  gen_random_uuid(),
  la.tenant_id,
  la.assignment_id,
  la.employee_id,
  la.site_id,
  la.shift_id,
  dc.config_id,
  dc.snap,
  cd.check_date,
  idx.n,
  (cd.check_date + '10:00'::TIME + ((idx.n - 1) * INTERVAL '3 hours'))::TIMESTAMPTZ,
  (cd.check_date + '10:00'::TIME + ((idx.n - 1) * INTERVAL '3 hours') + INTERVAL '5 minutes')::TIMESTAMPTZ,
  CASE
    WHEN (hashtext(la.assignment_id::text || cd.check_date::text || idx.n::text) % 5) = 0 THEN 'no_response'
    WHEN (hashtext(la.assignment_id::text || cd.check_date::text || idx.n::text) % 5) = 1 THEN 'responded'
    ELSE 'responded'
  END,
  NOW() - '7 days'::INTERVAL,
  NOW() - '7 days'::INTERVAL
FROM limited_assignments la
JOIN demo_configs         dc ON dc.tenant_id = la.tenant_id
CROSS JOIN check_dates    cd
CROSS JOIN (VALUES (1), (2)) AS idx(n)
ON CONFLICT (assignment_id, check_date, check_index) WHERE deleted_at IS NULL DO NOTHING;

-- ── Check Responses (for 'responded' checks) ──────────────────────────────────

INSERT INTO check_responses (
  id, tenant_id, scheduled_check_id, employee_id,
  responded_at, latitude, longitude, accuracy_meters,
  face_image_url, liveness_score,
  location_verified, face_verified, liveness_verified, face_verify_score,
  outcome, failure_reason,
  created_at
)
SELECT
  gen_random_uuid(),
  sc.tenant_id,
  sc.id,
  sc.employee_id,
  sc.scheduled_at + INTERVAL '2 minutes',
  COALESCE(s.latitude,  21.0285) + ((hashtext(sc.id::text || 'lat') % 50) - 25)::FLOAT / 100000.0,
  COALESCE(s.longitude, 105.8542) + ((hashtext(sc.id::text || 'lon') % 50) - 25)::FLOAT / 100000.0,
  5.0 + ((hashtext(sc.id::text) % 100) / 10.0),
  NULL,
  NULL,
  TRUE,
  NULL,
  NULL,
  NULL,
  'pass',
  NULL,
  sc.scheduled_at + INTERVAL '2 minutes'
FROM scheduled_checks sc
JOIN sites s ON s.id = sc.site_id AND s.deleted_at IS NULL
WHERE sc.status = 'responded'
  AND sc.deleted_at IS NULL
  AND sc.tenant_id IN (
    SELECT id FROM tenants
    WHERE slug IN ('acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc')
      AND deleted_at IS NULL
  )
ON CONFLICT (scheduled_check_id) DO NOTHING;

-- ── Violations (for no_response checks) ──────────────────────────────────────

INSERT INTO violations (
  id, tenant_id, employee_id, site_id,
  scheduled_check_id, check_response_id,
  violation_type, check_date, description,
  resolved, resolved_at, resolved_by,
  employee_note, employee_photo_url,
  resolution, resolution_reason,
  affects_attendance,
  created_at, updated_at
)
SELECT
  gen_random_uuid(),
  sc.tenant_id,
  sc.employee_id,
  sc.site_id,
  sc.id,
  NULL,
  'no_response',
  sc.check_date,
  'Nhân viên không phản hồi kiểm tra ngẫu nhiên trong thời gian quy định.',
  -- ~60% resolved
  (hashtext(sc.id::text || 'resolved') % 10) < 6,
  CASE WHEN (hashtext(sc.id::text || 'resolved') % 10) < 6
       THEN sc.expires_at + INTERVAL '2 hours'
       ELSE NULL END,
  CASE WHEN (hashtext(sc.id::text || 'resolved') % 10) < 6
       THEN (SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1)
       ELSE NULL END,
  NULL,
  NULL,
  CASE WHEN (hashtext(sc.id::text || 'resolved') % 10) < 6
       THEN CASE WHEN (hashtext(sc.id::text || 'res_type') % 3) = 0 THEN 'dismissed' ELSE 'confirmed' END
       ELSE NULL END,
  CASE WHEN (hashtext(sc.id::text || 'resolved') % 10) < 6
       THEN 'Đã được quản lý nhân sự xem xét.'
       ELSE NULL END,
  (hashtext(sc.id::text || 'affects') % 3) = 0,
  sc.expires_at,
  NOW()
FROM scheduled_checks sc
WHERE sc.status = 'no_response'
  AND sc.deleted_at IS NULL
  AND sc.tenant_id IN (
    SELECT id FROM tenants
    WHERE slug IN ('acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc')
      AND deleted_at IS NULL
  )
  AND NOT EXISTS (
    SELECT 1 FROM violations v WHERE v.scheduled_check_id = sc.id
  );

-- ── Additional Violations (location_fail from recent checks) ─────────────────

INSERT INTO violations (
  id, tenant_id, employee_id, site_id,
  scheduled_check_id, check_response_id,
  violation_type, check_date, description,
  resolved, resolved_at, resolved_by,
  employee_note, employee_photo_url,
  resolution, resolution_reason,
  affects_attendance,
  created_at, updated_at
)
SELECT
  gen_random_uuid(),
  cr.tenant_id,
  cr.employee_id,
  sc.site_id,
  cr.scheduled_check_id,
  cr.id,
  'location_fail',
  sc.check_date,
  'Nhân viên đã phản hồi nhưng vị trí GPS nằm ngoài khu vực geofence cho phép.',
  FALSE,
  NULL,
  NULL,
  'Tôi vẫn ở đúng vị trí làm việc nhưng tín hiệu GPS yếu.',
  NULL,
  NULL,
  NULL,
  FALSE,
  cr.responded_at,
  NOW()
FROM check_responses cr
JOIN scheduled_checks sc ON sc.id = cr.scheduled_check_id
WHERE cr.outcome = 'pass'
  AND cr.tenant_id IN (
    SELECT id FROM tenants
    WHERE slug IN ('acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc')
      AND deleted_at IS NULL
  )
  AND (hashtext(cr.id::text || 'viol') % 8) = 0   -- ~12% of pass responses get location_fail
  AND NOT EXISTS (
    SELECT 1 FROM violations v
    WHERE v.check_response_id = cr.id
  );

-- ── Multi-tenant person linking ───────────────────────────────────────────────
-- Two real-world scenario people who each work at two different tenants
-- under a single shared login (users.email is globally unique; employees
-- carry their own per-tenant work email, tenant_id, and employee_code).
-- Both share the platform admin's password hash so they can log in with
-- the same demo password (Admin@1234) for testing.

INSERT INTO users (id, email, password_hash, display_name, is_active, email_verified, is_platform_admin, created_at, updated_at)
SELECT gen_random_uuid(), 'dung.pham.hr@gmail.com',
       (SELECT password_hash FROM users WHERE email = 'admin@fams.com' LIMIT 1),
       'Phạm Thị Dung', TRUE, TRUE, FALSE,
       NOW() - '400 days'::INTERVAL, NOW()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'dung.pham.hr@gmail.com');

INSERT INTO users (id, email, password_hash, display_name, is_active, email_verified, is_platform_admin, created_at, updated_at)
SELECT gen_random_uuid(), 'truong.van.dat@gmail.com',
       (SELECT password_hash FROM users WHERE email = 'admin@fams.com' LIMIT 1),
       'Trương Văn Đạt', TRUE, TRUE, FALSE,
       NOW() - '200 days'::INTERVAL, NOW()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'truong.van.dat@gmail.com');

UPDATE employees e
SET user_id = (SELECT id FROM users WHERE email = 'dung.pham.hr@gmail.com')
FROM tenants t
WHERE e.tenant_id = t.id
  AND ((t.slug = 'acme-corp' AND e.employee_code = 'HL-004')
    OR (t.slug = 'beta-industries' AND e.employee_code = 'BM-003'))
  AND e.deleted_at IS NULL
  AND e.user_id IS NULL;

UPDATE employees e
SET user_id = (SELECT id FROM users WHERE email = 'truong.van.dat@gmail.com')
FROM tenants t
WHERE e.tenant_id = t.id
  AND ((t.slug = 'gamma-logistics' AND e.employee_code = 'PN-009')
    OR (t.slug = 'tia-sang-startup' AND e.employee_code = 'TS-004'))
  AND e.deleted_at IS NULL
  AND e.user_id IS NULL;

INSERT INTO user_roles (id, user_id, role_id, tenant_id, assigned_by, created_at, updated_at)
SELECT gen_random_uuid(),
       (SELECT id FROM users WHERE email = 'dung.pham.hr@gmail.com'),
       (SELECT id FROM roles WHERE tenant_id IS NULL AND name = 'HR_MANAGER'),
       t.id,
       (SELECT id FROM users WHERE is_platform_admin = TRUE LIMIT 1),
       NOW(), NOW()
FROM tenants t WHERE t.slug = 'acme-corp'
ON CONFLICT (user_id, role_id, tenant_id) DO NOTHING;

INSERT INTO user_roles (id, user_id, role_id, tenant_id, assigned_by, created_at, updated_at)
SELECT gen_random_uuid(),
       (SELECT id FROM users WHERE email = 'dung.pham.hr@gmail.com'),
       (SELECT id FROM roles WHERE tenant_id IS NULL AND name = 'SITE_SUPERVISOR'),
       t.id,
       (SELECT id FROM users WHERE is_platform_admin = TRUE LIMIT 1),
       NOW(), NOW()
FROM tenants t WHERE t.slug = 'beta-industries'
ON CONFLICT (user_id, role_id, tenant_id) DO NOTHING;

INSERT INTO user_roles (id, user_id, role_id, tenant_id, assigned_by, created_at, updated_at)
SELECT gen_random_uuid(),
       (SELECT id FROM users WHERE email = 'truong.van.dat@gmail.com'),
       (SELECT id FROM roles WHERE tenant_id IS NULL AND name = 'EMPLOYEE'),
       t.id,
       (SELECT id FROM users WHERE is_platform_admin = TRUE LIMIT 1),
       NOW(), NOW()
FROM tenants t WHERE t.slug = 'gamma-logistics'
ON CONFLICT (user_id, role_id, tenant_id) DO NOTHING;

INSERT INTO user_roles (id, user_id, role_id, tenant_id, assigned_by, created_at, updated_at)
SELECT gen_random_uuid(),
       (SELECT id FROM users WHERE email = 'truong.van.dat@gmail.com'),
       (SELECT id FROM roles WHERE tenant_id IS NULL AND name = 'EMPLOYEE'),
       t.id,
       (SELECT id FROM users WHERE is_platform_admin = TRUE LIMIT 1),
       NOW(), NOW()
FROM tenants t WHERE t.slug = 'tia-sang-startup'
ON CONFLICT (user_id, role_id, tenant_id) DO NOTHING;

-- ── Additional activated employee logins (single-tenant) ─────────────────────
-- Realistic mix: not every employee has activated a login account yet.
-- Give a handful per tenant a real user account matching their seniority,
-- so `users`/`user_roles` aren't limited to just the admin and the two
-- multi-tenant people above.

INSERT INTO users (id, email, password_hash, display_name, is_active, email_verified, is_platform_admin, created_at, updated_at)
SELECT gen_random_uuid(), v.login_email,
       (SELECT password_hash FROM users WHERE email = 'admin@fams.com' LIMIT 1),
       v.display_name, TRUE, TRUE, FALSE,
       NOW() - '180 days'::INTERVAL, NOW()
FROM (VALUES
  ('binh.tran@hoanglong.vn',  'Trần Thị Bình'),
  ('lan.bui@hoanglong.vn',    'Bùi Thị Lan'),
  ('giang.hoang@hoanglong.vn','Hoàng Thị Giang'),
  ('xuan.do@binhminh.vn',     'Đỗ Thị Xuân'),
  ('dat.le@binhminh.vn',      'Lê Văn Đạt'),
  ('loan.hoang@binhminh.vn',  'Hoàng Thị Loan'),
  ('hong.ly@phuongnam.vn',    'Lý Thị Hồng'),
  ('vinh.huynh@phuongnam.vn', 'Huỳnh Văn Vinh'),
  ('tung.cao@phuongnam.vn',   'Cao Văn Tùng'),
  ('ngan.nguyen@tiasang.vn',  'Nguyễn Thị Kim Ngân'),
  ('hanh.bach@donga.vn',      'Bạch Thị Hạnh')
) AS v(login_email, display_name)
WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.email = v.login_email);

-- Link each of these users back to their existing employee row (same tenant,
-- matched by work email) and grant the matching tenant role.
UPDATE employees e
SET user_id = u.id
FROM users u
WHERE e.email = u.email
  AND e.user_id IS NULL
  AND u.email IN (
    'binh.tran@hoanglong.vn','lan.bui@hoanglong.vn','giang.hoang@hoanglong.vn',
    'xuan.do@binhminh.vn','dat.le@binhminh.vn','loan.hoang@binhminh.vn',
    'hong.ly@phuongnam.vn','vinh.huynh@phuongnam.vn','tung.cao@phuongnam.vn',
    'ngan.nguyen@tiasang.vn','hanh.bach@donga.vn'
  );

INSERT INTO user_roles (id, user_id, role_id, tenant_id, assigned_by, created_at, updated_at)
SELECT gen_random_uuid(), e.user_id, r.id, e.tenant_id,
       (SELECT id FROM users WHERE is_platform_admin = TRUE LIMIT 1),
       NOW() - '180 days'::INTERVAL, NOW()
FROM (VALUES
  ('binh.tran@hoanglong.vn',   'SITE_SUPERVISOR'),
  ('lan.bui@hoanglong.vn',     'EMPLOYEE'),
  ('giang.hoang@hoanglong.vn', 'EMPLOYEE'),
  ('xuan.do@binhminh.vn',      'SITE_SUPERVISOR'),
  ('dat.le@binhminh.vn',       'EMPLOYEE'),
  ('loan.hoang@binhminh.vn',   'EMPLOYEE'),
  ('hong.ly@phuongnam.vn',     'TENANT_ADMIN'),
  ('vinh.huynh@phuongnam.vn',  'EMPLOYEE'),
  ('tung.cao@phuongnam.vn',    'EMPLOYEE'),
  ('ngan.nguyen@tiasang.vn',   'TENANT_ADMIN'),
  ('hanh.bach@donga.vn',       'TENANT_ADMIN')
) AS v(login_email, role_name)
JOIN users u ON u.email = v.login_email
JOIN employees e ON e.user_id = u.id
JOIN roles r ON r.tenant_id IS NULL AND r.name = v.role_name
ON CONFLICT (user_id, role_id, tenant_id) DO NOTHING;

-- ── Tenant Settings ────────────────────────────────────────────────────────────

INSERT INTO tenant_settings (
  id, tenant_id, date_format, time_format,
  brand_primary_color, brand_secondary_color, brand_accent_color,
  employee_code_prefix, employee_code_padding, employee_code_seq,
  created_at, updated_at
)
SELECT gen_random_uuid(), t.id, 'DD/MM/YYYY', 'HH:mm',
       v.primary_color, v.secondary_color, v.accent_color, v.prefix, 4, v.seq,
       NOW() - '60 days'::INTERVAL, NOW()
FROM tenants t
JOIN (VALUES
  ('acme-corp',         '#B22222', '#FFFFFF', '#FFD700', 'HL', 15),
  ('beta-industries',   '#1E90FF', '#FFFFFF', '#FFA500', 'BM', 15),
  ('gamma-logistics',   '#228B22', '#FFFFFF', '#00CED1', 'PN', 15),
  ('tia-sang-startup',  '#8A2BE2', '#FFFFFF', '#FF69B4', 'TS', 5),
  ('dong-a-jsc',        '#708090', '#FFFFFF', '#20B2AA', 'DA', 4)
) AS v(slug, primary_color, secondary_color, accent_color, prefix, seq) ON v.slug = t.slug
ON CONFLICT (tenant_id) DO NOTHING;

-- ── Face Verify Requests (ad-hoc, outside scheduled checks) ──────────────────

INSERT INTO face_verify_requests (
  id, tenant_id, employee_id, status, face_verified, liveness_verified,
  face_verify_score, error_code, requires_liveness, expires_at, created_at, updated_at
)
SELECT
  gen_random_uuid(), e.tenant_id, e.id,
  CASE (hashtext(e.id::text || 'fvr') % 3)
    WHEN 0 THEN 'pending' WHEN 1 THEN 'fail' ELSE 'pass' END,
  CASE WHEN (hashtext(e.id::text || 'fvr') % 3) = 2 THEN TRUE
       WHEN (hashtext(e.id::text || 'fvr') % 3) = 1 THEN FALSE ELSE NULL END,
  CASE WHEN (hashtext(e.id::text || 'fvr') % 3) = 2 THEN TRUE
       WHEN (hashtext(e.id::text || 'fvr') % 3) = 1 THEN FALSE ELSE NULL END,
  CASE WHEN (hashtext(e.id::text || 'fvr') % 3) <> 0
       THEN 0.70 + ((hashtext(e.id::text || 'score') % 30) / 100.0)
       ELSE NULL END,
  CASE WHEN (hashtext(e.id::text || 'fvr') % 3) = 1 THEN 'LOW_CONFIDENCE' ELSE NULL END,
  TRUE,
  NOW() - ((hashtext(e.id::text || 'exp') % 10)::TEXT || ' days')::INTERVAL + INTERVAL '10 minutes',
  NOW() - ((hashtext(e.id::text || 'exp') % 10)::TEXT || ' days')::INTERVAL,
  NOW()
FROM employees e
JOIN tenants t ON t.id = e.tenant_id
WHERE t.slug IN ('acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc')
  AND e.deleted_at IS NULL
  AND (hashtext(e.id::text || 'has_fvr') % 3) = 0  -- ~1/3 of employees have an ad-hoc verify request
  AND NOT EXISTS (SELECT 1 FROM face_verify_requests fvr WHERE fvr.employee_id = e.id);

-- ── Employee Invitations (expired) ────────────────────────────────────────────
-- Pending / cancelled invitations are created through the real API in
-- seed.sh. 'expired' status is easiest to backfill directly since it
-- would otherwise require waiting for expires_at to actually pass.

INSERT INTO employee_invitations (
  id, tenant_id, email, token, status, invited_by, role_id,
  first_name, last_name, phone, expires_at, created_at, updated_at
)
SELECT gen_random_uuid(), t.id, 'vi.pham.het.han@hoanglong.vn', gen_random_uuid(), 'expired',
       (SELECT id FROM users WHERE is_platform_admin = TRUE LIMIT 1), NULL,
       'Vi', 'Phạm Thị', '+84906111113',
       NOW() - '20 days'::INTERVAL, NOW() - '27 days'::INTERVAL, NOW() - '20 days'::INTERVAL
FROM tenants t
WHERE t.slug = 'acme-corp'
  AND NOT EXISTS (
    SELECT 1 FROM employee_invitations ei
    WHERE ei.tenant_id = t.id AND ei.email = 'vi.pham.het.han@hoanglong.vn'
  );

INSERT INTO employee_invitations (
  id, tenant_id, email, token, status, invited_by, role_id,
  first_name, last_name, phone, expires_at, created_at, updated_at
)
SELECT gen_random_uuid(), t.id, 'khanh.ly.het.han@phuongnam.vn', gen_random_uuid(), 'expired',
       (SELECT id FROM users WHERE is_platform_admin = TRUE LIMIT 1), NULL,
       'Khánh', 'Lý Văn', '+84906111114',
       NOW() - '15 days'::INTERVAL, NOW() - '22 days'::INTERVAL, NOW() - '15 days'::INTERVAL
FROM tenants t
WHERE t.slug = 'gamma-logistics'
  AND NOT EXISTS (
    SELECT 1 FROM employee_invitations ei
    WHERE ei.tenant_id = t.id AND ei.email = 'khanh.ly.het.han@phuongnam.vn'
  );

-- ── Notifications ─────────────────────────────────────────────────────────────
-- Create demo notifications targeting the platform admin for each tenant.
-- Employees without accepted invitations don't have user accounts, so we use
-- the admin user as the notification recipient for demo data.

WITH
  admin_user AS (
    SELECT id FROM users
    WHERE is_platform_admin = TRUE AND deleted_at IS NULL
    LIMIT 1
  ),
  demo_tenants_list AS (
    SELECT id AS tenant_id, slug
    FROM tenants
    WHERE slug IN ('acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc')
      AND deleted_at IS NULL
  ),
  notification_types AS (
    SELECT * FROM (VALUES
      ('attendance.late_checkin',    'Phát hiện chấm công trễ',
       'Nguyễn Văn An đã chấm công trễ 12 phút. Bản ghi chấm công đã được cập nhật.'),
      ('attendance.missing_checkout','Phát hiện thiếu chấm công ra',
       'Trần Thị Bình chưa chấm công ra tại Trụ sở Hoàng Long Hà Nội hôm nay.'),
      ('randomcheck.dispatched',     'Đã gửi kiểm tra ngẫu nhiên',
       'Đã gửi yêu cầu xác nhận vị trí tới Vũ Văn Hùng. Đang chờ phản hồi.'),
      ('randomcheck.no_response',    'Kiểm tra ngẫu nhiên — Không phản hồi',
       'Đặng Văn Khôi không phản hồi kiểm tra đã lên lịch trong vòng 5 phút.'),
      ('violation.raised',           'Vi phạm tuân thủ mới',
       'Đã ghi nhận vi phạm không phản hồi (no_response) cho Ngô Văn Minh.'),
      ('violation.confirmed',        'Vi phạm đã được xác nhận',
       'Bộ phận Nhân sự đã xác nhận vi phạm của Hoàng Thị Giang. Bản ghi chấm công bị ảnh hưởng.'),
      ('violation.dismissed',        'Vi phạm đã được bác bỏ',
       'Vi phạm gần đây của Đỗ Thị Ngọc đã được bộ phận Nhân sự bác bỏ.'),
      ('assignment.created',         'Phân công mới',
       'Vũ Văn Hùng đã được phân công vào Trụ sở Hoàng Long Hà Nội — Ca sáng.'),
      ('attendance.ot_detected',     'Phát hiện làm thêm giờ',
       'Nguyễn Văn An đã tích lũy 45 phút làm thêm giờ hôm nay.'),
      ('system.announcement',        'Thông báo bảo trì hệ thống',
       'Bảo trì định kỳ vào thứ Bảy 02:00-04:00. Dịch vụ có thể tạm gián đoạn.'),
      ('report.ready',               'Báo cáo tháng đã sẵn sàng',
       'Báo cáo chấm công tháng 6/2026 đã sẵn sàng để tải xuống.'),
      ('face_id.enrolled',           'Đã đăng ký Face ID',
       'Cao Văn Tùng đã đăng ký hồ sơ Face ID thành công.'),
      ('face_id.revoked',            'Face ID đã bị thu hồi',
       'Hồ sơ Face ID của Lê Văn Cường đã bị thu hồi theo yêu cầu.')
    ) AS t(event_type, title, body)
  )
INSERT INTO notifications (
  id, tenant_id, user_id, event_type, title, body,
  is_read, read_at, created_at
)
SELECT
  gen_random_uuid(),
  dt.tenant_id,
  au.id,
  nt.event_type,
  nt.title,
  nt.body,
  -- ~60% read
  (hashtext(dt.slug || nt.event_type) % 5) < 3,
  CASE WHEN (hashtext(dt.slug || nt.event_type) % 5) < 3
       THEN NOW() - ((hashtext(dt.slug || nt.event_type || 'h') % 12 + 1) || ' hours')::INTERVAL
       ELSE NULL END,
  NOW() - ((hashtext(dt.slug || nt.event_type || 'd') % 14 + 1) || ' days')::INTERVAL
FROM demo_tenants_list dt
CROSS JOIN notification_types nt
CROSS JOIN admin_user au
WHERE NOT EXISTS (
  SELECT 1 FROM notifications n
  WHERE n.tenant_id = dt.tenant_id
    AND n.user_id = au.id
    AND n.event_type = nt.event_type
);

-- ── Notification Templates ────────────────────────────────────────────────────

INSERT INTO notification_templates (
  id, tenant_id, event_type, locale, title_template, body_template,
  created_at, updated_at
)
SELECT
  gen_random_uuid(),
  t.id,
  ev.event_type,
  ev.locale,
  ev.title_tmpl,
  ev.body_tmpl,
  NOW() - '15 days'::INTERVAL,
  NOW()
FROM tenants t
CROSS JOIN (
  VALUES
    ('attendance.late_checkin', 'vi',
     'Chấm công trễ: {{employeeName}}',
     '{{employeeName}} đã chấm công trễ {{lateMinutes}} phút vào ngày {{date}}.'),
    ('attendance.missing_checkout', 'vi',
     'Thiếu giờ ra: {{employeeName}}',
     '{{employeeName}} chưa chấm công ra tại {{siteName}} vào ngày {{date}}.'),
    ('randomcheck.dispatched', 'vi',
     'Yêu cầu kiểm tra vị trí',
     'Vui lòng xác nhận vị trí của bạn trong vòng {{windowSeconds}} giây.'),
    ('violation.raised', 'vi',
     'Vi phạm tuân thủ: {{violationType}}',
     'Đã ghi nhận vi phạm {{violationType}} cho {{employeeName}} vào ngày {{date}}.'),
    ('violation.dismissed', 'vi',
     'Vi phạm đã được bác bỏ',
     'Vi phạm ngày {{date}} đã được bác bỏ: {{reason}}'),
    ('assignment.created', 'vi',
     'Phân công mới tại {{siteName}}',
     'Bạn đã được phân công tại {{siteName}}, ca {{shiftName}}, bắt đầu từ {{startDate}}.'),
    ('attendance.late_checkin', 'en',
     'Late Check-In: {{employeeName}}',
     '{{employeeName}} checked in {{lateMinutes}} minutes late on {{date}}.'),
    ('attendance.missing_checkout', 'en',
     'Missing Check-Out: {{employeeName}}',
     '{{employeeName}} did not check out from {{siteName}} on {{date}}.'),
    ('randomcheck.dispatched', 'en',
     'Location Check Required',
     'Please confirm your location within {{windowSeconds}} seconds.')
) AS ev(event_type, locale, title_tmpl, body_tmpl)
WHERE t.slug IN ('acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc')
  AND t.deleted_at IS NULL
ON CONFLICT (tenant_id, event_type, locale) DO NOTHING;

-- ── User Notification Settings ─────────────────────────────────────────────────

INSERT INTO user_notification_settings (id, user_id, event_type, in_app_enabled, push_enabled, created_at, updated_at)
SELECT gen_random_uuid(), u.id, s.event_type, s.in_app, s.push, NOW() - '30 days'::INTERVAL, NOW()
FROM users u
JOIN (VALUES
  ('admin@fams.com',           'system.announcement',      TRUE,  TRUE),
  ('admin@fams.com',           'report.ready',             TRUE,  FALSE),
  ('dung.pham.hr@gmail.com',   'attendance.late_checkin',  TRUE,  TRUE),
  ('dung.pham.hr@gmail.com',   'violation.raised',         TRUE,  TRUE),
  ('truong.van.dat@gmail.com', 'randomcheck.dispatched',   TRUE,  FALSE),
  ('truong.van.dat@gmail.com', 'assignment.created',       FALSE, TRUE)
) AS s(email, event_type, in_app, push) ON s.email = u.email
ON CONFLICT (user_id, event_type) DO NOTHING;

-- ── User Devices (FCM tokens) ──────────────────────────────────────────────────

INSERT INTO user_devices (id, user_id, device_token, platform, created_at, updated_at)
SELECT gen_random_uuid(), u.id, d.token, 'FCM', NOW() - '20 days'::INTERVAL, NOW()
FROM users u
JOIN (VALUES
  ('admin@fams.com',           'demo-fcm-token-admin-0001'),
  ('dung.pham.hr@gmail.com',   'demo-fcm-token-dung-0001'),
  ('truong.van.dat@gmail.com', 'demo-fcm-token-dat-0001')
) AS d(email, token) ON d.email = u.email
WHERE NOT EXISTS (
  SELECT 1 FROM user_devices ud WHERE ud.device_token = d.token AND ud.deleted_at IS NULL
);

-- ── Notification Delivery Logs ─────────────────────────────────────────────────
-- ~20% of admin notifications get a first failed attempt, followed by a
-- successful retry, to exercise the FCM retry/fallback path.

INSERT INTO notification_delivery_logs (id, notification_id, device_token, channel, attempt_number, status, error_message, created_at)
SELECT
  gen_random_uuid(), n.id, 'demo-fcm-token-admin-0001', 'FCM', 1,
  CASE WHEN (hashtext(n.id::text) % 5) = 0 THEN 'FAILED' ELSE 'SUCCESS' END,
  CASE WHEN (hashtext(n.id::text) % 5) = 0 THEN 'Device token unregistered' ELSE NULL END,
  n.created_at + INTERVAL '5 seconds'
FROM notifications n
WHERE n.user_id = (SELECT id FROM users WHERE is_platform_admin = TRUE LIMIT 1)
  AND NOT EXISTS (SELECT 1 FROM notification_delivery_logs dl WHERE dl.notification_id = n.id);

INSERT INTO notification_delivery_logs (id, notification_id, device_token, channel, attempt_number, status, error_message, created_at)
SELECT gen_random_uuid(), dl.notification_id, dl.device_token, dl.channel, 2, 'SUCCESS', NULL, dl.created_at + INTERVAL '30 seconds'
FROM notification_delivery_logs dl
WHERE dl.status = 'FAILED' AND dl.attempt_number = 1
  AND NOT EXISTS (
    SELECT 1 FROM notification_delivery_logs dl2
    WHERE dl2.notification_id = dl.notification_id AND dl2.attempt_number = 2
  );

-- ── Audit Logs ────────────────────────────────────────────────────────────────

INSERT INTO audit_logs (
  id, tenant_id, actor_id, actor_email,
  entity_type, entity_id, action,
  old_value, new_value,
  request_id, ip_address, user_agent,
  created_at
)
SELECT
  gen_random_uuid(),
  t.id,
  a.actor_id,
  a.actor_email,
  a.entity_type,
  gen_random_uuid()::TEXT,
  a.action,
  a.old_val,
  a.new_val,
  ('req-' || substr(gen_random_uuid()::TEXT, 1, 8)),
  ('192.168.' || ((hashtext(t.slug || a.action) % 254) + 1)::TEXT || '.1'),
  'FAMS-Web/2.0 (Demo Seed)',
  NOW() - ((hashtext(t.slug || a.action || a.entity_type) % 20) || ' days')::INTERVAL
FROM tenants t
CROSS JOIN (
  VALUES
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Employee',   'CREATE', NULL::JSONB,
     '{"status":"active","department":"Kỹ thuật"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Employee',   'UPDATE', '{"status":"active"}'::JSONB,
     '{"status":"inactive"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Site',       'CREATE', NULL::JSONB,
     '{"name":"Trụ sở chính","status":"active"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Assignment', 'CREATE', NULL::JSONB,
     '{"role":"worker","status":"active"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Shift',      'CREATE', NULL::JSONB,
     '{"name":"Ca sáng","startTime":"07:00"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Violation',  'UPDATE', '{"resolved":false}'::JSONB,
     '{"resolved":true,"resolution":"confirmed"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Tenant',     'SUSPEND', '{"status":"trial"}'::JSONB,
     '{"status":"suspended"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'RandomCheckConfig', 'CREATE', NULL::JSONB,
     '{"checkMode":"location_only","checksPerShift":2}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Checkin',    'UPDATE', '{"status":"pending_review"}'::JSONB,
     '{"status":"valid","overrideReason":"GPS was temporarily unavailable"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Department', 'CREATE', NULL::JSONB,
     '{"name":"Kỹ thuật"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Workspace',  'CREATE', NULL::JSONB,
     '{"name":"Phòng Kỹ thuật","type":"department"}'::JSONB)
) AS a(actor_id, actor_email, entity_type, action, old_val, new_val)
WHERE t.slug IN ('acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc')
  AND t.deleted_at IS NULL
  AND a.actor_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM audit_logs al
    WHERE al.tenant_id    = t.id
      AND al.entity_type  = a.entity_type
      AND al.action       = a.action
  );

-- ── Update some pending_review checkins ──────────────────────────────────────
-- Add explanation notes to checkins that are flagged for review.

UPDATE checkins SET
  employee_note = 'Tôi vẫn ở đúng vị trí làm việc. Tín hiệu GPS bị yếu do ở trong tòa nhà. Vui lòng xác minh giúp.',
  employee_photo_url = 'https://example.com/photos/checkin-explanation-demo.jpg'
WHERE status = 'pending_review'
  AND employee_note IS NULL
  AND tenant_id IN (
    SELECT id FROM tenants
    WHERE slug IN ('acme-corp', 'beta-industries', 'gamma-logistics', 'tia-sang-startup', 'dong-a-jsc')
      AND deleted_at IS NULL
  )
  AND (hashtext(id::text || 'explain') % 2) = 0;

COMMIT;
