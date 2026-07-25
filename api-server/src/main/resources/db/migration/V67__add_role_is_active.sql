-- Adds a distinct "deactivate" concept for custom (tenant-owned) roles, separate from
-- soft-delete: a role can be turned off from being assigned to NEW users while its EXISTING
-- holders keep using it — useful when retiring a role gradually instead of being forced to
-- revoke everyone first (delete already blocks while any active assignment exists).
-- System roles are unaffected in practice: RoleService blocks any update to isSystem roles,
-- so this column only ever toggles for custom tenant roles.
ALTER TABLE roles ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
