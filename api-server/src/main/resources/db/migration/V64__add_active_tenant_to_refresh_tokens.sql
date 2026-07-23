-- Issue #3 (docs/issues/ISSUES.md): a user may hold roles in multiple tenants. Previously,
-- login (and every subsequent token refresh) always picked the OLDEST role assignment as the
-- JWT's tenant context, with no way to actually work as a different company — refreshing the
-- access token silently reverted any "switch company" back to the original tenant.
--
-- active_tenant_id remembers which tenant this specific session (device) is currently
-- operating as, so POST /auth/switch-tenant can persist a switch that survives token refresh.
-- NULL means "no explicit switch yet — fall back to the oldest role assignment", preserving
-- existing behavior for every session created before this column existed.
ALTER TABLE refresh_tokens ADD COLUMN active_tenant_id UUID;

COMMENT ON COLUMN refresh_tokens.active_tenant_id IS
    'Tenant this session is currently scoped to (multi-tenant switch). NULL = default to oldest role assignment.';
