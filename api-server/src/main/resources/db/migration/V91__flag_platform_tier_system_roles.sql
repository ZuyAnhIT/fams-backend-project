-- Distinguishes platform-tier system roles (PLATFORM_ADMIN, PLATFORM_STAFF) from tenant-tier
-- system roles (TENANT_ADMIN, HR_MANAGER, SITE_SUPERVISOR, EMPLOYEE) — both groups previously
-- shared the exact same "tenant_id IS NULL, is_system = true" signature, so code had no way to
-- tell them apart. This caused two real bugs:
--   1. Company Admins viewing their tenant's role list saw PLATFORM_ADMIN/PLATFORM_STAFF too,
--      including their full permission list — internal FAMS governance info leaked to every
--      customer.
--   2. POST /user-roles (tenant-scoped role assignment) had no check blocking assignment of
--      PLATFORM_ADMIN/PLATFORM_STAFF within a tenant — a Company Admin with roles:update could
--      grant themselves or anyone else all 77 PLATFORM_ADMIN permissions. Verified exploitable
--      live before this fix.

ALTER TABLE roles ADD COLUMN is_platform_role BOOLEAN NOT NULL DEFAULT false;

UPDATE roles
SET is_platform_role = true
WHERE tenant_id IS NULL AND is_system = true AND name IN ('PLATFORM_ADMIN', 'PLATFORM_STAFF');
