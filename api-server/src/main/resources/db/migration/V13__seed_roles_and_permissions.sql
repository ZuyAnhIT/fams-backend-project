-- Seed default system permissions and roles.
-- System roles have tenant_id IS NULL and is_system = TRUE.
-- Uses INSERT … WHERE NOT EXISTS so re-runs are safe during development.

-- ─── 1. PERMISSIONS ───────────────────────────────────────────────────────────

INSERT INTO permissions (name, resource, action, description)
SELECT v.name, v.resource, v.action, v.description
FROM (VALUES
    ('users:create',             'users',        'create',    'Create users'),
    ('users:read',               'users',        'read',      'View a user'),
    ('users:update',             'users',        'update',    'Update users'),
    ('users:delete',             'users',        'delete',    'Delete users'),
    ('users:list',               'users',        'list',      'List users'),

    ('employees:create',         'employees',    'create',    'Create employees'),
    ('employees:read',           'employees',    'read',      'View an employee'),
    ('employees:update',         'employees',    'update',    'Update employees'),
    ('employees:delete',         'employees',    'delete',    'Delete employees'),
    ('employees:list',           'employees',    'list',      'List employees'),

    ('sites:create',             'sites',        'create',    'Create sites'),
    ('sites:read',               'sites',        'read',      'View a site'),
    ('sites:update',             'sites',        'update',    'Update sites'),
    ('sites:delete',             'sites',        'delete',    'Delete sites'),
    ('sites:list',               'sites',        'list',      'List sites'),

    ('shifts:create',            'shifts',       'create',    'Create shifts'),
    ('shifts:read',              'shifts',       'read',      'View a shift'),
    ('shifts:update',            'shifts',       'update',    'Update shifts'),
    ('shifts:delete',            'shifts',       'delete',    'Delete shifts'),
    ('shifts:list',              'shifts',       'list',      'List shifts'),

    ('assignments:create',       'assignments',  'create',    'Create assignments'),
    ('assignments:read',         'assignments',  'read',      'View an assignment'),
    ('assignments:update',       'assignments',  'update',    'Update assignments'),
    ('assignments:delete',       'assignments',  'delete',    'Delete assignments'),
    ('assignments:list',         'assignments',  'list',      'List assignments'),

    ('checkins:create',          'checkins',     'create',    'Submit a check-in'),
    ('checkins:read',            'checkins',     'read',      'View a check-in'),
    ('checkins:list',            'checkins',     'list',      'List check-ins'),

    ('attendance:read',          'attendance',   'read',      'View attendance record'),
    ('attendance:list',          'attendance',   'list',      'List attendance records'),
    ('attendance:export',        'attendance',   'export',    'Export attendance data'),

    ('randomchecks:create',      'randomchecks', 'create',    'Create spot-check'),
    ('randomchecks:read',        'randomchecks', 'read',      'View a spot-check'),
    ('randomchecks:list',        'randomchecks', 'list',      'List spot-checks'),
    ('randomchecks:configure',   'randomchecks', 'configure', 'Configure spot-check settings'),

    ('violations:create',        'violations',   'create',    'Create violations'),
    ('violations:read',          'violations',   'read',      'View a violation'),
    ('violations:update',        'violations',   'update',    'Update violations'),
    ('violations:list',          'violations',   'list',      'List violations'),

    ('reports:read',             'reports',      'read',      'View reports'),
    ('reports:list',             'reports',      'list',      'List reports'),
    ('reports:export',           'reports',      'export',    'Export reports'),

    ('notifications:read',       'notifications','read',      'Read notifications'),
    ('notifications:list',       'notifications','list',      'List notifications'),

    ('audit:read',               'audit',        'read',      'View audit log entry'),
    ('audit:list',               'audit',        'list',      'List audit log'),

    ('tenants:create',           'tenants',      'create',    'Create tenants (platform)'),
    ('tenants:read',             'tenants',      'read',      'View a tenant (platform)'),
    ('tenants:update',           'tenants',      'update',    'Update tenants (platform)'),
    ('tenants:list',             'tenants',      'list',      'List tenants (platform)'),

    ('plans:create',             'plans',        'create',    'Create plans (platform)'),
    ('plans:read',               'plans',        'read',      'View a plan (platform)'),
    ('plans:update',             'plans',        'update',    'Update plans (platform)'),
    ('plans:list',               'plans',        'list',      'List plans (platform)'),

    ('roles:create',             'roles',        'create',    'Create roles'),
    ('roles:read',               'roles',        'read',      'View a role'),
    ('roles:update',             'roles',        'update',    'Update roles'),
    ('roles:delete',             'roles',        'delete',    'Delete roles'),
    ('roles:list',               'roles',        'list',      'List roles'),

    ('permissions:read',         'permissions',  'read',      'View a permission'),
    ('permissions:list',         'permissions',  'list',      'List permissions')
) AS v(name, resource, action, description)
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = v.name);

-- ─── 2. SYSTEM ROLES ──────────────────────────────────────────────────────────

INSERT INTO roles (name, description, is_system)
SELECT v.name, v.description, TRUE
FROM (VALUES
    ('PLATFORM_ADMIN',   'Platform administrator with unrestricted access'),
    ('TENANT_ADMIN',     'Company administrator managing all tenant resources'),
    ('HR_MANAGER',       'HR manager handling employees, attendance and violations'),
    ('SITE_SUPERVISOR',  'Site supervisor overseeing check-ins and spot-checks'),
    ('EMPLOYEE',         'Regular field employee')
) AS v(name, description)
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = v.name AND tenant_id IS NULL);

-- ─── 3. ROLE → PERMISSION ASSIGNMENTS ────────────────────────────────────────

-- PLATFORM_ADMIN: all permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'PLATFORM_ADMIN' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;

-- TENANT_ADMIN: everything except platform-only (tenants:*, plans:*)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN (
    'users:create','users:read','users:update','users:delete','users:list',
    'employees:create','employees:read','employees:update','employees:delete','employees:list',
    'sites:create','sites:read','sites:update','sites:delete','sites:list',
    'shifts:create','shifts:read','shifts:update','shifts:delete','shifts:list',
    'assignments:create','assignments:read','assignments:update','assignments:delete','assignments:list',
    'checkins:create','checkins:read','checkins:list',
    'attendance:read','attendance:list','attendance:export',
    'randomchecks:create','randomchecks:read','randomchecks:list','randomchecks:configure',
    'violations:create','violations:read','violations:update','violations:list',
    'reports:read','reports:list','reports:export',
    'notifications:read','notifications:list',
    'audit:read','audit:list',
    'roles:create','roles:read','roles:update','roles:delete','roles:list',
    'permissions:read','permissions:list'
)
WHERE r.name = 'TENANT_ADMIN' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;

-- HR_MANAGER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN (
    'employees:create','employees:read','employees:update','employees:list',
    'attendance:read','attendance:list','attendance:export',
    'violations:create','violations:read','violations:update','violations:list',
    'reports:read','reports:list','reports:export',
    'shifts:read','shifts:list',
    'assignments:create','assignments:read','assignments:update','assignments:list',
    'notifications:read','notifications:list',
    'audit:read','audit:list'
)
WHERE r.name = 'HR_MANAGER' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;

-- SITE_SUPERVISOR
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN (
    'sites:read','sites:list',
    'checkins:create','checkins:read','checkins:list',
    'randomchecks:create','randomchecks:read','randomchecks:list','randomchecks:configure',
    'violations:create','violations:read','violations:list',
    'employees:read','employees:list',
    'shifts:read','shifts:list',
    'attendance:read','attendance:list',
    'notifications:read','notifications:list'
)
WHERE r.name = 'SITE_SUPERVISOR' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;

-- EMPLOYEE
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN (
    'checkins:create','checkins:read',
    'attendance:read',
    'notifications:read','notifications:list'
)
WHERE r.name = 'EMPLOYEE' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;
