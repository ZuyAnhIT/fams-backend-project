-- ============================================================
-- FAMS Historical Seed Data
-- ============================================================
-- Inserts realistic demo data for the past 30 days across the
-- three demo tenants (acme-corp, beta-industries, gamma-logistics).
--
-- Tables populated:
--   face_profiles, checkins, attendance_summaries,
--   random_check_configs (site-level overrides),
--   scheduled_checks, check_responses, violations,
--   notifications, audit_logs
--
-- Safe to run multiple times: all inserts use ON CONFLICT DO NOTHING.
-- ============================================================

BEGIN;

-- ── Face Profiles ─────────────────────────────────────────────────────────────
-- One profile per employee; statuses vary for demo coverage.

INSERT INTO face_profiles (
    id, tenant_id, employee_id,
    consent_given, consent_given_at,
    status, embedding, embedding_deleted,
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
        WHEN 1 THEN 'pending'
        WHEN 2 THEN 'revoked'
        ELSE      'enrolled'
    END,
    NULL,
    FALSE,
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
WHERE t.slug IN ('acme-corp', 'beta-industries', 'gamma-logistics')
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
  AND s.code IN ('ACME-HN', 'GAMMA-N')
  AND s.deleted_at IS NULL
ON CONFLICT DO NOTHING;

-- ── Historical Checkins (past 30 weekdays) ────────────────────────────────────

WITH
  demo_tenants AS (
    SELECT id FROM tenants
    WHERE slug IN ('acme-corp', 'beta-industries', 'gamma-logistics')
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
  NULL,
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
      WHERE slug IN ('acme-corp', 'beta-industries', 'gamma-logistics')
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
      AND t.slug IN ('acme-corp', 'beta-industries', 'gamma-logistics')
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
    WHERE slug IN ('acme-corp', 'beta-industries', 'gamma-logistics')
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
  'Employee did not respond to scheduled random check within the required window.',
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
       THEN 'Reviewed by HR manager.'
       ELSE NULL END,
  (hashtext(sc.id::text || 'affects') % 3) = 0,
  sc.expires_at,
  NOW()
FROM scheduled_checks sc
WHERE sc.status = 'no_response'
  AND sc.deleted_at IS NULL
  AND sc.tenant_id IN (
    SELECT id FROM tenants
    WHERE slug IN ('acme-corp', 'beta-industries', 'gamma-logistics')
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
  'Employee responded but GPS location was outside the permitted geofence boundary.',
  FALSE,
  NULL,
  NULL,
  'I was at the correct location but my GPS signal was weak.',
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
    WHERE slug IN ('acme-corp', 'beta-industries', 'gamma-logistics')
      AND deleted_at IS NULL
  )
  AND (hashtext(cr.id::text || 'viol') % 8) = 0   -- ~12% of pass responses get location_fail
  AND NOT EXISTS (
    SELECT 1 FROM violations v
    WHERE v.check_response_id = cr.id
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
    WHERE slug IN ('acme-corp', 'beta-industries', 'gamma-logistics')
      AND deleted_at IS NULL
  ),
  notification_types AS (
    SELECT * FROM (VALUES
      ('attendance.late_checkin',    'Late Check-In Detected',
       'Alice Walker checked in 12 minutes late. Attendance record updated.'),
      ('attendance.missing_checkout','Missing Check-Out Detected',
       'Bob Smith did not check out from Hanoi HQ site today.'),
      ('randomcheck.dispatched',     'Random Check Dispatched',
       'Location check dispatched to Henry Chen. Awaiting response.'),
      ('randomcheck.no_response',    'Random Check — No Response',
       'Ivan Rodriguez did not respond to the scheduled check within 5 minutes.'),
      ('violation.raised',           'New Compliance Violation',
       'A no_response violation has been recorded for Kevin Lee on 2026-07-10.'),
      ('violation.confirmed',        'Violation Confirmed',
       'HR has confirmed the violation for Grace Kim. Attendance record affected.'),
      ('violation.dismissed',        'Violation Dismissed',
       'The recent violation for Laura Martinez has been dismissed by HR.'),
      ('assignment.created',         'New Assignment',
       'Henry Chen has been assigned to Hanoi HQ — Morning Shift.'),
      ('attendance.ot_detected',     'Overtime Detected',
       'Alice Walker accumulated 45 minutes of overtime today.'),
      ('system.announcement',        'System Maintenance Window',
       'Scheduled maintenance Saturday 02:00-04:00. Service may be briefly unavailable.'),
      ('report.ready',               'Monthly Report Ready',
       'June 2026 attendance report is ready for download.'),
      ('face_id.enrolled',           'Face ID Enrolled',
       'Samuel Wilson has successfully enrolled their Face ID profile.'),
      ('face_id.revoked',            'Face ID Revoked',
       'Face ID profile for Charlie Jones has been revoked per request.')
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
    ('attendance.late_checkin', 'en',
     'Late Check-In: {{employeeName}}',
     '{{employeeName}} checked in {{lateMinutes}} minutes late on {{date}}.'),
    ('attendance.missing_checkout', 'en',
     'Missing Check-Out: {{employeeName}}',
     '{{employeeName}} did not check out from {{siteName}} on {{date}}.'),
    ('randomcheck.dispatched', 'en',
     'Location Check Required',
     'Please confirm your location within {{windowSeconds}} seconds.'),
    ('violation.raised', 'en',
     'Compliance Violation: {{violationType}}',
     'A {{violationType}} violation was recorded for {{employeeName}} on {{date}}.'),
    ('violation.dismissed', 'en',
     'Violation Dismissed',
     'Violation from {{date}} has been dismissed: {{reason}}'),
    ('assignment.created', 'en',
     'New Assignment at {{siteName}}',
     'You have been assigned to {{siteName}} on {{shiftName}} starting {{startDate}}.'),
    ('attendance.late_checkin', 'vi',
     'Chấm công trễ: {{employeeName}}',
     '{{employeeName}} đã chấm công trễ {{lateMinutes}} phút vào ngày {{date}}.'),
    ('attendance.missing_checkout', 'vi',
     'Thiếu giờ ra: {{employeeName}}',
     '{{employeeName}} chưa chấm công ra tại {{siteName}} vào ngày {{date}}.'),
    ('randomcheck.dispatched', 'vi',
     'Kiểm tra vị trí',
     'Vui lòng xác nhận vị trí trong vòng {{windowSeconds}} giây.')
) AS ev(event_type, locale, title_tmpl, body_tmpl)
WHERE t.slug IN ('acme-corp', 'beta-industries', 'gamma-logistics')
  AND t.deleted_at IS NULL
ON CONFLICT (tenant_id, event_type, locale) DO NOTHING;

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
     '{"status":"active","department":"Engineering"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Employee',   'UPDATE', '{"status":"active"}'::JSONB,
     '{"status":"inactive"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Site',       'CREATE', NULL::JSONB,
     '{"name":"Main Site","status":"active"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Assignment', 'CREATE', NULL::JSONB,
     '{"role":"worker","status":"active"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Shift',      'CREATE', NULL::JSONB,
     '{"name":"Morning Shift","startTime":"07:00"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Violation',  'UPDATE', '{"resolved":false}'::JSONB,
     '{"resolved":true,"resolution":"confirmed"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Tenant',     'UPDATE', '{"status":"trial"}'::JSONB,
     '{"status":"active"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'RandomCheckConfig', 'CREATE', NULL::JSONB,
     '{"checkMode":"location_only","checksPerShift":2}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Checkin',    'UPDATE', '{"status":"pending_review"}'::JSONB,
     '{"status":"valid","overrideReason":"GPS was temporarily unavailable"}'::JSONB),
    ((SELECT id FROM users WHERE is_platform_admin = TRUE AND deleted_at IS NULL LIMIT 1),
     'admin@fams.com', 'Workspace',  'CREATE', NULL::JSONB,
     '{"name":"Engineering","type":"department"}'::JSONB)
) AS a(actor_id, actor_email, entity_type, action, old_val, new_val)
WHERE t.slug IN ('acme-corp', 'beta-industries', 'gamma-logistics')
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
  employee_note = 'I was at the correct location. My GPS signal was weak due to a building. Please verify.',
  employee_photo_url = 'https://example.com/photos/checkin-explanation-demo.jpg'
WHERE status = 'pending_review'
  AND employee_note IS NULL
  AND tenant_id IN (
    SELECT id FROM tenants
    WHERE slug IN ('acme-corp', 'beta-industries', 'gamma-logistics')
      AND deleted_at IS NULL
  )
  AND (hashtext(id::text || 'explain') % 2) = 0;

COMMIT;
