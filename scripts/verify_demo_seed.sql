-- Database-level acceptance checks for scripts/seed_demo.sql.
-- psql is executed with ON_ERROR_STOP=1, so any failed assertion fails the seed command.

CREATE OR REPLACE FUNCTION pg_temp.seed_uuid(seed_key TEXT)
RETURNS UUID
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT uuid_generate_v5(uuid_ns_url(), 'https://fams.local/demo/v3/' || seed_key)
$$;

DO $$
DECLARE
    primary_tenant_id UUID;
    actual_count INTEGER;
BEGIN
    SELECT id INTO primary_tenant_id
    FROM tenants
    WHERE slug = 'demo-an-phat' AND deleted_at IS NULL;

    IF primary_tenant_id IS NULL THEN
        RAISE EXCEPTION 'Seed validation failed: primary tenant demo-an-phat is missing';
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM tenants
    WHERE slug IN ('demo-an-phat','demo-minh-long','demo-sao-viet','demo-phuc-hung','demo-bac-nam')
      AND deleted_at IS NULL;
    IF actual_count <> 5 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 5 demo tenants, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM employees
    WHERE tenant_id = primary_tenant_id AND status = 'active' AND deleted_at IS NULL
      AND id IN (SELECT pg_temp.seed_uuid('employee:demo-an-phat:AP' || LPAD(n::TEXT, 3, '0'))
                 FROM generate_series(1, 15) n);
    IF actual_count <> 15 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 15 active An Phat employees, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM employees e
    JOIN users u ON u.id = e.user_id
    WHERE e.tenant_id = primary_tenant_id
      AND e.deleted_at IS NULL
      AND u.is_active = TRUE
      AND u.email_verified = TRUE
      AND u.deleted_at IS NULL
      AND e.id IN (SELECT pg_temp.seed_uuid('employee:demo-an-phat:AP' || LPAD(n::TEXT, 3, '0'))
                   FROM generate_series(1, 15) n);
    IF actual_count <> 15 THEN
        RAISE EXCEPTION 'Seed validation failed: all 15 employees must have active, verified logins; found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM users
    WHERE email LIKE '%@fams.test'
      AND (is_active = FALSE OR email_verified = FALSE OR deleted_at IS NOT NULL OR is_platform_admin = TRUE);
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'Seed validation failed: % reserved demo accounts are inactive, unverified, deleted or platform admins', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM users u
    WHERE u.is_platform_admin = TRUE
      AND (
          EXISTS (SELECT 1 FROM tenants t WHERE t.owner_id = u.id AND t.deleted_at IS NULL)
          OR EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.id AND ur.tenant_id IS NOT NULL AND ur.deleted_at IS NULL)
          OR EXISTS (SELECT 1 FROM employees e WHERE e.user_id = u.id AND e.deleted_at IS NULL)
      );
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'Seed validation failed: Platform Admin is linked to company data';
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM (
        SELECT e.id
        FROM employees e
        JOIN user_roles ur ON ur.user_id = e.user_id
                          AND ur.tenant_id = e.tenant_id
                          AND ur.deleted_at IS NULL
        WHERE e.tenant_id = primary_tenant_id AND e.deleted_at IS NULL
          AND e.id IN (SELECT pg_temp.seed_uuid('employee:demo-an-phat:AP' || LPAD(n::TEXT, 3, '0'))
                       FROM generate_series(1, 15) n)
        GROUP BY e.id
        HAVING COUNT(*) = 1
    ) correctly_scoped;
    IF actual_count <> 15 THEN
        RAISE EXCEPTION 'Seed validation failed: every employee must have exactly one active company role; valid=%', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM user_roles ur
    JOIN roles r ON r.id = ur.role_id
    WHERE ur.tenant_id = primary_tenant_id AND ur.deleted_at IS NULL
      AND r.name = 'TENANT_ADMIN'
      AND ur.user_id IN (SELECT user_id FROM employees e WHERE e.id = pg_temp.seed_uuid('employee:demo-an-phat:AP001'));
    IF actual_count <> 1 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 1 TENANT_ADMIN, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM user_roles ur JOIN roles r ON r.id = ur.role_id
    WHERE ur.tenant_id = primary_tenant_id AND ur.deleted_at IS NULL AND r.name = 'HR_MANAGER'
      AND ur.user_id IN (SELECT user_id FROM employees e
                         WHERE e.id IN (pg_temp.seed_uuid('employee:demo-an-phat:AP002'),
                                        pg_temp.seed_uuid('employee:demo-an-phat:AP003')));
    IF actual_count <> 2 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 2 HR_MANAGER accounts, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM user_roles ur JOIN roles r ON r.id = ur.role_id
    WHERE ur.tenant_id = primary_tenant_id AND ur.deleted_at IS NULL AND r.name = 'SITE_SUPERVISOR'
      AND ur.user_id IN (SELECT user_id FROM employees e
                         WHERE e.employee_code BETWEEN 'AP004' AND 'AP007'
                           AND e.id = pg_temp.seed_uuid('employee:demo-an-phat:' || e.employee_code));
    IF actual_count <> 4 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 4 SITE_SUPERVISOR accounts, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM user_roles ur JOIN roles r ON r.id = ur.role_id
    WHERE ur.tenant_id = primary_tenant_id AND ur.deleted_at IS NULL AND r.name = 'EMPLOYEE'
      AND ur.user_id IN (SELECT user_id FROM employees e
                         WHERE e.employee_code BETWEEN 'AP008' AND 'AP015'
                           AND e.id = pg_temp.seed_uuid('employee:demo-an-phat:' || e.employee_code));
    IF actual_count <> 8 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 8 EMPLOYEE accounts, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM user_role_sites urs
    JOIN user_roles ur ON ur.id = urs.user_role_id
    JOIN roles r ON r.id = ur.role_id AND r.name = 'SITE_SUPERVISOR'
    JOIN employees e ON e.user_id = ur.user_id AND e.tenant_id = ur.tenant_id
    WHERE ur.tenant_id = primary_tenant_id AND ur.deleted_at IS NULL
      AND e.employee_code BETWEEN 'AP004' AND 'AP007'
      AND e.id = pg_temp.seed_uuid('employee:demo-an-phat:' || e.employee_code);
    IF actual_count <> 4 THEN
        RAISE EXCEPTION 'Seed validation failed: all 4 supervisors must have exactly one site scope; scopes=%', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM workspaces
    WHERE tenant_id = primary_tenant_id AND type = 'department'
      AND status = 'active' AND deleted_at IS NULL
      AND id IN (SELECT pg_temp.seed_uuid('workspace:demo-an-phat:' || code)
                 FROM (VALUES ('board'),('hr'),('engineering'),('safety'),('operations')) d(code));
    IF actual_count <> 5 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 5 active departments, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM workspace_members
    WHERE tenant_id = primary_tenant_id AND is_primary = TRUE AND deleted_at IS NULL
      AND employee_id IN (SELECT pg_temp.seed_uuid('employee:demo-an-phat:AP' || LPAD(n::TEXT, 3, '0'))
                          FROM generate_series(1, 15) n);
    IF actual_count <> 15 THEN
        RAISE EXCEPTION 'Seed validation failed: expected one primary department for every employee; found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM sites
    WHERE tenant_id = primary_tenant_id AND status = 'active' AND deleted_at IS NULL
      AND code IN ('AP-HQ','AP-TH','AP-CG','AP-DA');
    IF actual_count <> 4 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 4 active sites, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM assignments
    WHERE tenant_id = primary_tenant_id AND status = 'active' AND deleted_at IS NULL
      AND id IN (SELECT pg_temp.seed_uuid('assignment:AP' || LPAD(n::TEXT, 3, '0'))
                 FROM generate_series(4, 15) n);
    IF actual_count <> 12 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 12 operational assignments, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM assignments a
    JOIN employees e ON e.id = a.employee_id
    JOIN sites s ON s.id = a.site_id
    JOIN shifts sh ON sh.id = a.shift_id
    WHERE a.tenant_id = primary_tenant_id
      AND (e.tenant_id <> a.tenant_id OR s.tenant_id <> a.tenant_id OR sh.tenant_id <> a.tenant_id);
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'Seed validation failed: assignment contains a cross-tenant reference';
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM tenants t
    LEFT JOIN tenant_subscriptions ts ON ts.tenant_id = t.id
    WHERE t.slug IN ('demo-an-phat','demo-minh-long','demo-sao-viet','demo-phuc-hung','demo-bac-nam')
      AND t.deleted_at IS NULL
      AND (t.timezone <> 'Asia/Ho_Chi_Minh' OR t.currency_code <> 'VND'
           OR ts.id IS NULL);
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'Seed validation failed: timezone, currency or subscription is inconsistent';
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM tenant_subscriptions ts JOIN tenants t ON t.id = ts.tenant_id
    WHERE t.slug IN ('demo-an-phat','demo-minh-long','demo-sao-viet','demo-phuc-hung','demo-bac-nam')
      AND t.deleted_at IS NULL
      AND ((t.slug IN ('demo-an-phat','demo-minh-long') AND ts.status = 'ACTIVE')
        OR (t.slug = 'demo-sao-viet' AND ts.status = 'CANCELLED')
        OR (t.slug = 'demo-phuc-hung' AND ts.status = 'TRIAL')
        OR (t.slug = 'demo-bac-nam' AND ts.status = 'EXPIRED'));
    IF actual_count <> 5 THEN
        RAISE EXCEPTION 'Seed validation failed: subscription lifecycle matrix is incomplete; valid=%', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM checkins c JOIN employees e ON e.id = c.employee_id
    WHERE c.tenant_id = primary_tenant_id AND c.deleted_at IS NULL
      AND c.id = pg_temp.seed_uuid('checkin:' || e.employee_code || ':' ||
          (c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE)
      AND (c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE
          BETWEEN '2026-07-15' AND '2026-09-05';
    IF actual_count <> 524 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 524 deterministic attendance sessions, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count FROM (
        SELECT date_trunc('month', c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh') month_key
        FROM checkins c JOIN employees e ON e.id = c.employee_id
        WHERE c.tenant_id = primary_tenant_id AND c.deleted_at IS NULL
          AND c.id = pg_temp.seed_uuid('checkin:' || e.employee_code || ':' ||
              (c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE)
        GROUP BY month_key
    ) history_months;
    IF actual_count <> 3 THEN
        RAISE EXCEPTION 'Seed validation failed: attendance must cover July, August and September; months=%', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count FROM attendance_summaries ats
    WHERE ats.tenant_id = primary_tenant_id
      AND ats.attendance_date BETWEEN '2026-07-15' AND '2026-09-05'
      AND (ats.is_late OR ats.is_early_leave OR ats.ot_minutes > 0 OR ats.missing_checkout
           OR ats.has_pending_review_session OR ats.has_rejected_session);
    IF actual_count < 50 THEN
        RAISE EXCEPTION 'Seed validation failed: attendance scenarios are not diverse enough; exceptional rows=%', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count FROM (
        WITH days AS (
            SELECT generate_series('2026-07-15'::DATE, '2026-09-05'::DATE, INTERVAL '1 day')::DATE work_date
        ), expected AS (
            SELECT a.employee_id, a.site_id, d.work_date
            FROM days d JOIN assignments a
              ON a.tenant_id = primary_tenant_id AND a.status = 'active' AND a.deleted_at IS NULL
             AND a.id IN (SELECT pg_temp.seed_uuid('assignment:AP' || LPAD(n::TEXT, 3, '0'))
                          FROM generate_series(4, 15) n)
             AND a.start_date <= d.work_date AND (a.end_date IS NULL OR a.end_date >= d.work_date)
             AND (a.days_of_week IS NULL
                  OR (a.days_of_week & (1 << (EXTRACT(ISODOW FROM d.work_date)::INTEGER - 1))) <> 0)
        )
        SELECT ex.* FROM expected ex
        LEFT JOIN attendance_summaries ats
          ON ats.tenant_id = primary_tenant_id AND ats.employee_id = ex.employee_id
         AND ats.site_id = ex.site_id AND ats.attendance_date = ex.work_date
         AND ats.deleted_at IS NULL
        WHERE ats.id IS NULL
    ) absences;
    IF actual_count <> 20 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 20 planned absences, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count FROM scheduled_checks sc
    WHERE sc.tenant_id = primary_tenant_id AND sc.config_snapshot->>'seedVersion' = 'v4';
    IF actual_count <> 18 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 18 random checks, found %', actual_count;
    END IF;

    SELECT COUNT(DISTINCT v.violation_type) INTO actual_count
    FROM violations v JOIN scheduled_checks sc ON sc.id = v.scheduled_check_id
    WHERE v.tenant_id = primary_tenant_id AND v.deleted_at IS NULL
      AND sc.config_snapshot->>'seedVersion' = 'v4';
    IF actual_count <> 5 THEN
        RAISE EXCEPTION 'Seed validation failed: all 5 violation categories must be represented; found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count FROM billing_orders b
    WHERE b.payment_link_id LIKE 'demo-link-%';
    IF actual_count <> 13 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 13 platform billing orders, found %', actual_count;
    END IF;

    SELECT COUNT(DISTINCT date_trunc('month', b.paid_at AT TIME ZONE 'Asia/Ho_Chi_Minh')) INTO actual_count
    FROM billing_orders b WHERE b.payment_link_id LIKE 'demo-link-%' AND b.status = 'PAID';
    IF actual_count <> 3 THEN
        RAISE EXCEPTION 'Seed validation failed: collected revenue must cover 3 months; months=%', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count FROM billing_orders b
    WHERE b.payment_link_id LIKE 'demo-link-%'
      AND ((b.status <> 'PAID' AND b.invoice_status IN ('PENDING_ISSUANCE','ISSUED'))
        OR (b.status = 'PAID' AND b.invoice_status = 'NOT_ELIGIBLE'));
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'Seed validation failed: billing invoice lifecycle is inconsistent; rows=%', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM checkins c
    WHERE c.tenant_id = primary_tenant_id
      AND c.check_out_at IS NULL
      AND c.session_closed_at IS NULL
      AND c.deleted_at IS NULL;
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'Seed validation failed: stale open check-in sessions found=%', actual_count;
    END IF;
END $$;

SELECT 'demo_tenants' AS metric, COUNT(*)::TEXT AS value
FROM tenants
WHERE slug IN ('demo-an-phat','demo-minh-long','demo-sao-viet','demo-phuc-hung','demo-bac-nam') AND deleted_at IS NULL
UNION ALL
SELECT 'curated_an_phat_members', COUNT(*)::TEXT
FROM employees e JOIN tenants t ON t.id = e.tenant_id
WHERE t.slug = 'demo-an-phat' AND e.deleted_at IS NULL
  AND e.id = pg_temp.seed_uuid('employee:demo-an-phat:' || e.employee_code)
  AND e.employee_code BETWEEN 'AP001' AND 'AP015'
UNION ALL
SELECT 'curated_an_phat_departments', COUNT(*)::TEXT
FROM workspaces w JOIN tenants t ON t.id = w.tenant_id
WHERE t.slug = 'demo-an-phat' AND w.deleted_at IS NULL
  AND w.id IN (SELECT pg_temp.seed_uuid('workspace:demo-an-phat:' || code)
               FROM (VALUES ('board'),('hr'),('engineering'),('safety'),('operations')) d(code))
UNION ALL
SELECT 'curated_an_phat_sites', COUNT(*)::TEXT
FROM sites s JOIN tenants t ON t.id = s.tenant_id
WHERE t.slug = 'demo-an-phat' AND s.deleted_at IS NULL
  AND s.code IN ('AP-HQ','AP-TH','AP-CG','AP-DA')
UNION ALL
SELECT 'curated_an_phat_assignments', COUNT(*)::TEXT
FROM assignments a JOIN tenants t ON t.id = a.tenant_id JOIN employees e ON e.id = a.employee_id
WHERE t.slug = 'demo-an-phat' AND a.deleted_at IS NULL
  AND a.id = pg_temp.seed_uuid('assignment:' || e.employee_code)
  AND e.employee_code BETWEEN 'AP004' AND 'AP015'
UNION ALL
SELECT 'curated_checkins_2026_07_15_to_09_05', COUNT(*)::TEXT
FROM checkins c JOIN tenants t ON t.id = c.tenant_id JOIN employees e ON e.id = c.employee_id
WHERE t.slug = 'demo-an-phat' AND c.deleted_at IS NULL
  AND (c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE BETWEEN '2026-07-15' AND '2026-09-05'
  AND c.id = pg_temp.seed_uuid('checkin:' || e.employee_code || ':' ||
      (c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE)
  AND e.employee_code BETWEEN 'AP004' AND 'AP015'
UNION ALL
SELECT 'curated_attendance_exceptions', COUNT(*)::TEXT
FROM attendance_summaries ats JOIN tenants t ON t.id = ats.tenant_id
JOIN employees e ON e.id = ats.employee_id
WHERE t.slug = 'demo-an-phat' AND ats.deleted_at IS NULL
  AND ats.attendance_date BETWEEN '2026-07-15' AND '2026-09-05'
  AND e.employee_code BETWEEN 'AP004' AND 'AP015'
  AND (ats.is_late OR ats.is_early_leave OR ats.ot_minutes > 0 OR ats.missing_checkout
       OR ats.has_pending_review_session OR ats.has_rejected_session)
UNION ALL
SELECT 'history_random_checks', COUNT(*)::TEXT
FROM scheduled_checks sc JOIN tenants t ON t.id = sc.tenant_id
WHERE t.slug = 'demo-an-phat' AND sc.config_snapshot->>'seedVersion' = 'v4'
UNION ALL
SELECT 'platform_billing_orders', COUNT(*)::TEXT
FROM billing_orders WHERE payment_link_id LIKE 'demo-link-%';
