-- Replace the tenant-role name uniqueness index to exclude soft-deleted rows,
-- allowing a name to be reused after a role is soft-deleted.
DROP INDEX IF EXISTS uq_roles_tenant_name;
DROP INDEX IF EXISTS uq_roles_system_name;

CREATE UNIQUE INDEX uq_roles_system_name
    ON roles (name)
    WHERE tenant_id IS NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX uq_roles_tenant_name
    ON roles (tenant_id, name)
    WHERE tenant_id IS NOT NULL AND deleted_at IS NULL;
