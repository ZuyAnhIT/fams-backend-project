-- Platform accounts and company accounts are separate security scopes. Historical API test
-- scripts provisioned disposable tenants with admin@fams.com as owner, leaving hundreds of
-- TENANT_ADMIN memberships behind in the shared development database. Soft-delete only those
-- clearly identifiable test tenants so the cleanup remains recoverable and preserves genuine
-- Vietnamese sample companies and their owners.

CREATE TEMP TABLE cleanup_test_tenant_ids (
    id UUID PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO cleanup_test_tenant_ids (id)
SELECT t.id
FROM tenants t
JOIN users owner_user ON owner_user.id = t.owner_id
WHERE t.deleted_at IS NULL
  AND (
      owner_user.is_platform_admin = TRUE
      OR lower(owner_user.email) ~ '^(create_tenant_owner_|update_tenant_owner_|list_tenants_owner_|owner_assign_owner_|switch_corp[0-9]+_owner_|tenant_status_owner_|ip_whitelist_owner_|jwt_claim_owner_|err_corp_owner_|plan_limits_owner_|tenant_cancel_owner_|tenant_detail_owner_|tenant_settings_owner_|logo_owner_)'
      OR lower(owner_user.email) ~ '^viol\.detail\.owner\.'
  );

-- Remove company-scoped memberships before detaching owners. This prevents login/refresh from
-- selecting a tenant that is about to be archived.
UPDATE user_roles
SET deleted_at = COALESCE(deleted_at, now()),
    updated_at = now()
WHERE tenant_id IN (SELECT id FROM cleanup_test_tenant_ids)
  AND deleted_at IS NULL;

-- A Platform Admin must not retain any company-scoped role, even if an old record was not tied
-- to one of the disposable test tenants selected above.
UPDATE user_roles ur
SET deleted_at = COALESCE(ur.deleted_at, now()),
    updated_at = now()
FROM users u
WHERE ur.user_id = u.id
  AND u.is_platform_admin = TRUE
  AND ur.tenant_id IS NOT NULL
  AND ur.deleted_at IS NULL;

UPDATE refresh_tokens
SET active_tenant_id = NULL
WHERE active_tenant_id IN (SELECT id FROM cleanup_test_tenant_ids)
   OR user_id IN (SELECT id FROM users WHERE is_platform_admin = TRUE);

UPDATE tenants
SET owner_id = NULL,
    status = 'cancelled',
    deleted_at = COALESCE(deleted_at, now()),
    updated_at = now()
WHERE id IN (SELECT id FROM cleanup_test_tenant_ids);

-- Remove disposable plan records created by subscription integration tests from every plan
-- catalogue. Foreign-key history remains intact because this is a soft delete.
UPDATE plans
SET is_active = FALSE,
    deleted_at = COALESCE(deleted_at, now()),
    updated_at = now()
WHERE deleted_at IS NULL
  AND name ~ '^(test-(src|tgt|empty)-[0-9]+|custom(-limits)?-[0-9]+)$';
