-- Keep the platform user directory readable after integration-test runs. Archive only accounts
-- with explicit automation naming/domains and no ownership or active role in a live company.
-- This is a soft-delete cleanup; business/sample accounts remain untouched.

CREATE TEMP TABLE cleanup_test_user_ids (
    id UUID PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO cleanup_test_user_ids (id)
SELECT u.id
FROM users u
WHERE u.deleted_at IS NULL
  AND u.is_platform_admin = FALSE
  AND NOT EXISTS (
      SELECT 1 FROM tenants t
      WHERE t.owner_id = u.id AND t.deleted_at IS NULL
  )
  AND NOT EXISTS (
      SELECT 1
      FROM user_roles ur
      JOIN tenants t ON t.id = ur.tenant_id AND t.deleted_at IS NULL
      WHERE ur.user_id = u.id AND ur.deleted_at IS NULL
  )
  AND (
      lower(COALESCE(u.email, '')) LIKE '%@example.com'
      OR lower(COALESCE(u.email, '')) LIKE '%@fams-test.local'
      OR lower(COALESCE(u.email, '')) ~ '^(changepass_|err_corp_owner_|google_(link|only)_|jwt_claim_(subject|owner)_|searchable[.]user[.]|unverified_|profile(_test)?_|testuser_|resend_|sessions(_other|_others)?_|switchtest_|switch_corp[0-9]+_owner_|totp_hardening_|update_profile_[ab]_|create_tenant_owner_|ip_whitelist_owner_|list_tenants_owner_|owner_assign_owner_|plan_limits_owner_|tenant_detail_owner_|tenant_settings_owner_|tenant_status_owner_|tenant_cancel_owner_|update_tenant_owner_|create_role_regular_|platform_staff_|revoke_test_|noperm[._]|locktest_|logo_owner_)'
  );

UPDATE user_roles
SET deleted_at = COALESCE(deleted_at, now()),
    updated_at = now()
WHERE user_id IN (SELECT id FROM cleanup_test_user_ids)
  AND deleted_at IS NULL;

UPDATE refresh_tokens
SET revoked_at = COALESCE(revoked_at, now())
WHERE user_id IN (SELECT id FROM cleanup_test_user_ids)
  AND revoked_at IS NULL;

UPDATE users
SET is_active = FALSE,
    deleted_at = COALESCE(deleted_at, now()),
    updated_at = now()
WHERE id IN (SELECT id FROM cleanup_test_user_ids);
