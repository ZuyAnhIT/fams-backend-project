#!/usr/bin/env bash
# Tests for tenant owner assignment at creation time (24/07/2026 redesign — see
# TenantService.createTenant). Formerly (Issue #12) a creator naming a different
# ownerEmail sent that person an INVITATION even if they had no account yet. That's been
# replaced: platform-provisioning callers (Platform Admin, or Platform Staff with
# tenants:create) must name an EXISTING FAMS user (by ownerUserId or ownerEmail) who is
# DIRECTLY assigned TENANT_ADMIN — no invitation, 404 if the account doesn't exist — and
# self-service callers may not set an owner/plan at all.
# Usage: BASE_URL=http://localhost:8080 bash test_owner_invitation.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
TS=$(date +%s)

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "=== Tenant Owner Assignment Tests ==="
echo "Target: $BASE_URL"
echo ""

echo "--- Setup: login as platform admin ---"
ADMIN_LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
ADMIN_TOKEN=$(echo "$ADMIN_LOGIN" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$ADMIN_TOKEN" ] && echo "SETUP FAILED: no admin token" && exit 1
echo "Admin token obtained."

echo ""
echo "--- Setup: register the existing user who will become an owner ---"
OWNER_EMAIL="owner_assign_owner_${TS}@fams.com"
OWNER_TOKEN=$(register_verified_test_user_token "$BASE_URL" "Owner Assign Owner" "$OWNER_EMAIL")
OWNER_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -tA -c \
    "SELECT id FROM users WHERE email='$OWNER_EMAIL';")
echo "Owner registered: id=$OWNER_ID"

echo ""
echo "--- Test 1: Admin assigns owner by ownerEmail → 201, direct TENANT_ADMIN (no invitation) ---"
SLUG1="owner-by-email-${TS}"
resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"name\":\"Owner By Email Test $TS\",\"slug\":\"$SLUG1\",\"industry\":\"tech\",\"countryCode\":\"VN\",\"ownerEmail\":\"$OWNER_EMAIL\"}")
status=$(echo "$resp" | tail -n1); body=$(echo "$resp" | head -n -1)
TENANT_ID=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null || true)
OWNER_ID_IN_BODY=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['ownerId'])" 2>/dev/null || true)
if [ "$status" -eq 201 ] && [ "$OWNER_ID_IN_BODY" = "$OWNER_ID" ]; then
    pass "Tenant created, ownerId in response matches the assigned existing user"
else
    fail "Expected 201 with ownerId=$OWNER_ID, got status=$status ownerId=$OWNER_ID_IN_BODY body=$body"
fi

echo ""
echo "--- Test 2: Owner directly has TENANT_ADMIN — no invitation row was created ---"
owner_role=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "
SELECT r.name FROM user_roles ur
JOIN users u ON u.id = ur.user_id
JOIN roles r ON r.id = ur.role_id
WHERE ur.tenant_id = '$TENANT_ID' AND u.email = '$OWNER_EMAIL' AND ur.deleted_at IS NULL;" | tr -d ' ')
invite_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT count(*) FROM employee_invitations WHERE tenant_id='$TENANT_ID';" | tr -d ' ')
if [ "$owner_role" = "TENANT_ADMIN" ] && [ "$invite_count" = "0" ]; then
    pass "Owner has TENANT_ADMIN directly; 0 invitation rows (this is an assignment, not an invite)"
else
    fail "Expected TENANT_ADMIN + 0 invitations, got role='$owner_role' invites=$invite_count"
fi

echo ""
echo "--- Test 3: Admin (the provisioning caller) has NO membership in the tenant ---"
admin_role_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "
SELECT COUNT(*) FROM user_roles ur JOIN users u ON u.id = ur.user_id
WHERE ur.tenant_id = '$TENANT_ID' AND u.email = 'admin@fams.com' AND ur.deleted_at IS NULL;" | tr -d ' ')
if [ "$admin_role_count" = "0" ]; then
    pass "Provisioning admin was not made a member of the tenant it created"
else
    fail "Expected 0 admin memberships, got $admin_role_count"
fi

echo ""
echo "--- Test 4: Admin assigns owner by ownerUserId instead of email → 201 ---"
SLUG2="owner-by-id-${TS}"
resp2=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"name\":\"Owner By Id Test $TS\",\"slug\":\"$SLUG2\",\"industry\":\"tech\",\"countryCode\":\"VN\",\"ownerUserId\":\"$OWNER_ID\"}")
status2=$(echo "$resp2" | tail -n1); body2=$(echo "$resp2" | head -n -1)
OWNER_ID_IN_BODY2=$(echo "$body2" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['ownerId'])" 2>/dev/null || true)
if [ "$status2" -eq 201 ] && [ "$OWNER_ID_IN_BODY2" = "$OWNER_ID" ]; then
    pass "Same user can be assigned owner of a SECOND tenant via ownerUserId (owns multiple companies)"
else
    fail "Expected 201 with ownerId=$OWNER_ID, got status=$status2 body=$body2"
fi

echo ""
echo "--- Test 5: Admin create with NEITHER ownerUserId nor ownerEmail → 400 (owner now required) ---"
SLUG3="no-owner-${TS}"
status3=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"name\":\"No Owner Test $TS\",\"slug\":\"$SLUG3\",\"industry\":\"tech\",\"countryCode\":\"VN\"}")
if [ "$status3" -eq 400 ]; then
    pass "Admin create without an owner is rejected (HTTP 400)"
else
    fail "Expected 400, got $status3"
fi

echo ""
echo "--- Test 6: Admin create with a non-existent owner email → 404 (not an invitation) ---"
status4=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"name\":\"Ghost Owner Test $TS\",\"slug\":\"ghost-owner-${TS}\",\"industry\":\"tech\",\"countryCode\":\"VN\",\"ownerEmail\":\"ghost-${TS}@nowhere.example\"}")
if [ "$status4" -eq 404 ]; then
    pass "Non-existent owner email rejected (HTTP 404) — confirms this is a direct assignment, not an invite"
else
    fail "Expected 404, got $status4"
fi

echo ""
echo "--- Test 7: Self-service caller (no tenants:create) may NOT set ownerEmail → 403 ---"
SELF_TOKEN=$(register_verified_test_user_token "$BASE_URL" "Self Service Caller")
status5=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $SELF_TOKEN" -H "Content-Type: application/json" \
    -d "{\"name\":\"Self Escalation Test $TS\",\"slug\":\"self-escalation-${TS}\",\"ownerEmail\":\"$OWNER_EMAIL\"}")
if [ "$status5" -eq 403 ]; then
    pass "Self-service caller cannot assign someone else as owner (HTTP 403)"
else
    fail "Expected 403, got $status5"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
