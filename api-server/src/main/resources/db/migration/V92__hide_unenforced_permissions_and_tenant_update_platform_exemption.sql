-- RBAC audit follow-up (docs/reviews/backend/rbac-role-permission-audit-2026-08-13.md, mục 3):
-- 23 permissions exist in the catalog but are never read by any enforcement code — ticking them
-- when creating a custom role has zero effect, misleading Company Admins into thinking they
-- granted something they didn't. Non-destructive: keep the rows (in case enforcement is added
-- later), just stop offering them in the role-creation picker.

ALTER TABLE permissions ADD COLUMN is_assignable BOOLEAN NOT NULL DEFAULT true;

UPDATE permissions SET is_assignable = false
WHERE (resource, action) IN (
    ('assignments', 'read'),
    ('employees', 'delete'),
    ('geofences', 'delete'),
    ('notifications', 'list'),
    ('notifications', 'read'),
    ('permissions', 'list'),
    ('permissions', 'read'),
    ('plans', 'create'),
    ('plans', 'list'),
    ('plans', 'read'),
    ('plans', 'update'),
    ('randomchecks', 'create'),
    ('randomchecks', 'read'),
    ('reports', 'read'),
    ('roles', 'list'),
    ('shifts', 'read'),
    ('tenants', 'update'),
    ('users', 'delete'),
    ('users', 'list'),
    ('users', 'read'),
    ('users', 'update'),
    ('violations', 'create'),
    ('workspace_members', 'read')
);
