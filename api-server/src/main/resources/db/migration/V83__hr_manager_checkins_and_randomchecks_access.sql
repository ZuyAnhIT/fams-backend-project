-- Bug found via audit (2026-08-02, user stories: "HR kích hoạt kiểm tra ngay",
-- "HR xem danh sách/chi tiết scheduled checks", "HR override check-in"): HR_MANAGER was
-- seeded (V13) WITHOUT any checkins:* or randomchecks:* permission, while SITE_SUPERVISOR
-- and TENANT_ADMIN both have the full set. Every endpoint literally named "HR ..." in the
-- product backlog (POST .../scheduled-checks/manual, GET .../scheduled-checks,
-- GET .../scheduled-checks/{id}, PATCH .../checkins/{id}/override) was actually a 403 for
-- HR_MANAGER despite Javadoc/user-story text explicitly naming HR as the caller. Bring
-- HR_MANAGER's checkins/randomchecks grant in line with SITE_SUPERVISOR's — HR needs at
-- least the same operational visibility+trigger rights a site supervisor already has.

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN (
    'checkins:create', 'checkins:read', 'checkins:list',
    'randomchecks:create', 'randomchecks:read', 'randomchecks:list', 'randomchecks:configure'
)
WHERE r.name = 'HR_MANAGER' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;
