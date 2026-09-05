-- Database-level acceptance checks for scripts/seed_demo.sql.
-- psql is executed with ON_ERROR_STOP=1, so any failed assertion fails the seed command.

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
    WHERE slug IN ('demo-an-phat','demo-minh-long','demo-sao-viet')
      AND status = 'active' AND deleted_at IS NULL;
    IF actual_count <> 3 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 3 active demo tenants, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM employees
    WHERE tenant_id = primary_tenant_id AND status = 'active' AND deleted_at IS NULL;
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
      AND u.deleted_at IS NULL;
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
      AND r.name = 'TENANT_ADMIN';
    IF actual_count <> 1 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 1 TENANT_ADMIN, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM user_roles ur JOIN roles r ON r.id = ur.role_id
    WHERE ur.tenant_id = primary_tenant_id AND ur.deleted_at IS NULL AND r.name = 'HR_MANAGER';
    IF actual_count <> 2 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 2 HR_MANAGER accounts, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM user_roles ur JOIN roles r ON r.id = ur.role_id
    WHERE ur.tenant_id = primary_tenant_id AND ur.deleted_at IS NULL AND r.name = 'SITE_SUPERVISOR';
    IF actual_count <> 4 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 4 SITE_SUPERVISOR accounts, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM user_roles ur JOIN roles r ON r.id = ur.role_id
    WHERE ur.tenant_id = primary_tenant_id AND ur.deleted_at IS NULL AND r.name = 'EMPLOYEE';
    IF actual_count <> 8 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 8 EMPLOYEE accounts, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM user_role_sites urs
    JOIN user_roles ur ON ur.id = urs.user_role_id
    JOIN roles r ON r.id = ur.role_id AND r.name = 'SITE_SUPERVISOR'
    WHERE ur.tenant_id = primary_tenant_id AND ur.deleted_at IS NULL;
    IF actual_count <> 4 THEN
        RAISE EXCEPTION 'Seed validation failed: all 4 supervisors must have exactly one site scope; scopes=%', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM workspaces
    WHERE tenant_id = primary_tenant_id AND type = 'department'
      AND status = 'active' AND deleted_at IS NULL;
    IF actual_count <> 5 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 5 active departments, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM workspace_members
    WHERE tenant_id = primary_tenant_id AND is_primary = TRUE AND deleted_at IS NULL;
    IF actual_count <> 15 THEN
        RAISE EXCEPTION 'Seed validation failed: expected one primary department for every employee; found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM sites
    WHERE tenant_id = primary_tenant_id AND status = 'active' AND deleted_at IS NULL;
    IF actual_count <> 4 THEN
        RAISE EXCEPTION 'Seed validation failed: expected 4 active sites, found %', actual_count;
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM assignments
    WHERE tenant_id = primary_tenant_id AND status = 'active' AND deleted_at IS NULL;
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
    WHERE t.slug IN ('demo-an-phat','demo-minh-long','demo-sao-viet')
      AND t.deleted_at IS NULL
      AND (t.timezone <> 'Asia/Ho_Chi_Minh' OR t.currency_code <> 'VND'
           OR ts.id IS NULL OR ts.status <> 'ACTIVE');
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'Seed validation failed: timezone, currency or subscription is inconsistent';
    END IF;

    SELECT COUNT(*) INTO actual_count
    FROM checkins c
    WHERE c.tenant_id = primary_tenant_id
      AND c.deleted_at IS NULL
      AND (c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE < '2026-09-01';
    IF actual_count <> 0 THEN
        RAISE EXCEPTION 'Seed validation failed: attendance history starts before September 2026';
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

SELECT 'active_demo_tenants' AS metric, COUNT(*)::TEXT AS value
FROM tenants
WHERE slug IN ('demo-an-phat','demo-minh-long','demo-sao-viet') AND deleted_at IS NULL
UNION ALL
SELECT 'an_phat_members', COUNT(*)::TEXT
FROM employees e JOIN tenants t ON t.id = e.tenant_id
WHERE t.slug = 'demo-an-phat' AND e.deleted_at IS NULL
UNION ALL
SELECT 'an_phat_departments', COUNT(*)::TEXT
FROM workspaces w JOIN tenants t ON t.id = w.tenant_id
WHERE t.slug = 'demo-an-phat' AND w.deleted_at IS NULL
UNION ALL
SELECT 'an_phat_sites', COUNT(*)::TEXT
FROM sites s JOIN tenants t ON t.id = s.tenant_id
WHERE t.slug = 'demo-an-phat' AND s.deleted_at IS NULL
UNION ALL
SELECT 'an_phat_assignments', COUNT(*)::TEXT
FROM assignments a JOIN tenants t ON t.id = a.tenant_id
WHERE t.slug = 'demo-an-phat' AND a.deleted_at IS NULL
UNION ALL
SELECT 'september_checkins', COUNT(*)::TEXT
FROM checkins c JOIN tenants t ON t.id = c.tenant_id
WHERE t.slug = 'demo-an-phat' AND c.deleted_at IS NULL
  AND (c.check_in_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE >= '2026-09-01';
