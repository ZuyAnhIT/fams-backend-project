-- Platform staff can now provision a company on behalf of a customer (create the tenant,
-- assign an existing user as its owner, and pick a plan at creation time) — previously
-- V62 deliberately scoped PLATFORM_STAFF to read-only tenant access. Ongoing changes to an
-- already-created tenant's subscription/profile remain Platform-Admin-only; this permission
-- only covers the one-time creation flow (TenantService.createTenant).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name = 'tenants:create'
WHERE r.name = 'PLATFORM_STAFF' AND r.tenant_id IS NULL
ON CONFLICT DO NOTHING;
