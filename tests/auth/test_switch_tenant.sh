#!/usr/bin/env bash
# Tests for Issue #3 (docs/issues/ISSUES.md): POST /api/v1/auth/switch-tenant — lets a
# multi-tenant user actually operate as a different company, and persists the switch across
# token refresh (previously login/refresh always silently reverted to the oldest role
# assignment, with no way to work as any other tenant at all).
# Usage: BASE_URL=http://localhost:8080 bash test_switch_tenant.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

decode_claim() {
    # $1 = JWT, $2 = claim name
    local payload
    payload=$(echo "$1" | cut -d'.' -f2)
    local pad=$(( (4 - ${#payload} % 4) % 4 ))
    payload="${payload}$(printf '=%.0s' $(seq 1 $pad))"
    echo "$payload" | tr '_-' '/+' | base64 -d 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('$2',''))"
}

echo "=== Switch Tenant Tests (Issue #3) ==="
echo "Target: $BASE_URL"
echo ""

echo "--- Setup: Login as platform admin, create 2 tenants ---"
ADMIN_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -z "$ADMIN_TOKEN" ] && echo "SETUP FAILED: no admin token" && exit 1

TS=$(date +%s)
EMAIL="switchtest_${TS}@fams.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register" -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"TestPass1\",\"displayName\":\"Switch Test\"}"
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "UPDATE users SET email_verified = true WHERE email = '$EMAIL';" > /dev/null
USER_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "SELECT id FROM users WHERE email='$EMAIL';" | tr -d ' ')

# Platform-admin-provisioned tenants now require an existing-user owner (see
# TenantService.createTenant) — use a throwaway owner for all three, distinct from $EMAIL,
# since $EMAIL's TENANT_ADMIN role in T1/T2 (and deliberate absence in T3) is assigned
# explicitly below via POST /user-roles; assigning $EMAIL as owner here too would just
# duplicate that same role assignment.
register_throwaway_owner() {
    local email="$1"
    curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register" -H "Content-Type: application/json" \
        -d "{\"email\":\"$email\",\"password\":\"TestPass1\",\"displayName\":\"Throwaway Owner\"}"
    docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
        "UPDATE users SET email_verified = true WHERE email = '$email';" > /dev/null
}
T1_OWNER="switch_corp1_owner_${TS}@fams.com"; register_throwaway_owner "$T1_OWNER"
T2_OWNER="switch_corp2_owner_${TS}@fams.com"; register_throwaway_owner "$T2_OWNER"
T3_OWNER="switch_corp3_owner_${TS}@fams.com"; register_throwaway_owner "$T3_OWNER"

T1=$(curl -s -X POST "$BASE_URL/api/v1/tenants" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Switch Corp1 $TS\",\"slug\":\"switch-corp1-$TS\",\"ownerEmail\":\"$T1_OWNER\"}" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
T2=$(curl -s -X POST "$BASE_URL/api/v1/tenants" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Switch Corp2 $TS\",\"slug\":\"switch-corp2-$TS\",\"ownerEmail\":\"$T2_OWNER\"}" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
T3=$(curl -s -X POST "$BASE_URL/api/v1/tenants" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Switch Corp3 (no role) $TS\",\"slug\":\"switch-corp3-$TS\",\"ownerEmail\":\"$T3_OWNER\"}" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
echo "user=$USER_ID t1=$T1 t2=$T2 t3(no role)=$T3"

TENANT_ADMIN_ROLE=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT id FROM roles WHERE name='TENANT_ADMIN' AND tenant_id IS NULL;" | tr -d ' ')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/user-roles" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"userId\":\"$USER_ID\",\"roleId\":\"$TENANT_ADMIN_ROLE\",\"tenantId\":\"$T1\"}"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/user-roles" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"userId\":\"$USER_ID\",\"roleId\":\"$TENANT_ADMIN_ROLE\",\"tenantId\":\"$T2\"}"
echo ""

echo "--- Test 1: Login defaults to the oldest role assignment (tenant 1) ---"
LOGIN_RESP=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMAIL\",\"password\":\"TestPass1\"}")
ACCESS_TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
REFRESH_TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['refreshToken'])")
claim_tenant=$(decode_claim "$ACCESS_TOKEN" tenantId)
if [ "$claim_tenant" == "$T1" ]; then pass "Login token scoped to tenant 1 (oldest role)"; else fail "Expected tenantId=$T1, got $claim_tenant"; fi
echo ""

echo "--- Test 2: GET /roles/me returns both memberships with tenant names ---"
ROLES_RESP=$(curl -s "$BASE_URL/api/v1/roles/me" -H "Authorization: Bearer $ACCESS_TOKEN")
count=$(echo "$ROLES_RESP" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']))")
has_names=$(echo "$ROLES_RESP" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print(all(r.get('tenantName') for r in d))
")
if [ "$count" -eq 2 ] && [ "$has_names" == "True" ]; then
    pass "roles/me lists 2 memberships, each with a tenantName"
else
    fail "Expected 2 memberships with names, got count=$count has_names=$has_names: $ROLES_RESP"
fi
echo ""

echo "--- Test 3: Switch to tenant 2 succeeds, new token scoped to tenant 2 ---"
SWITCH_RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/switch-tenant" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ACCESS_TOKEN" \
    -d "{\"tenantId\":\"$T2\",\"refreshToken\":\"$REFRESH_TOKEN\"}")
switch_status=$(echo "$SWITCH_RESP" | tail -n1)
switch_body=$(echo "$SWITCH_RESP" | head -n -1)
if [ "$switch_status" -eq 200 ]; then
    NEW_ACCESS=$(echo "$switch_body" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
    NEW_REFRESH=$(echo "$switch_body" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['refreshToken'])")
    claim_tenant2=$(decode_claim "$NEW_ACCESS" tenantId)
    if [ "$claim_tenant2" == "$T2" ]; then pass "Switch to tenant 2 succeeds, token now scoped to tenant 2"; else fail "Expected tenantId=$T2, got $claim_tenant2"; fi
else
    fail "Expected 200, got $switch_status: $switch_body"
fi
echo ""

echo "--- Test 4: Old refresh token is revoked after switch (rotation) ---"
old_status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/refresh-token" \
    -H "Content-Type: application/json" -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}")
if [ "$old_status" -eq 401 ]; then pass "Old (pre-switch) refresh token rejected (401)"; else fail "Expected 401, got $old_status"; fi
echo ""

echo "--- Test 5: Token refresh after switch STICKS to tenant 2 (the actual bug being fixed) ---"
REFRESH_RESP=$(curl -s -X POST "$BASE_URL/api/v1/auth/refresh-token" -H "Content-Type: application/json" \
    -d "{\"refreshToken\":\"$NEW_REFRESH\"}")
REFRESHED_ACCESS=$(echo "$REFRESH_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
claim_after_refresh=$(decode_claim "$REFRESHED_ACCESS" tenantId)
if [ "$claim_after_refresh" == "$T2" ]; then
    pass "Refresh after switch still scoped to tenant 2 (no silent revert to oldest tenant)"
else
    fail "Expected tenantId=$T2 after refresh, got $claim_after_refresh — switch did not survive refresh"
fi
echo ""

echo "--- Test 6: Cannot switch to a tenant with no role there (403) ---"
NEWEST_REFRESH=$(echo "$REFRESH_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['refreshToken'])")
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/switch-tenant" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $REFRESHED_ACCESS" \
    -d "{\"tenantId\":\"$T3\",\"refreshToken\":\"$NEWEST_REFRESH\"}")
if [ "$status" -eq 403 ]; then pass "Switch to a tenant with no role there rejected (403)"; else fail "Expected 403, got $status"; fi
echo ""

echo "--- Test 7: Cannot switch with someone else's refresh token ---"
OTHER_LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
OTHER_REFRESH=$(echo "$OTHER_LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['refreshToken'])")
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/switch-tenant" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $REFRESHED_ACCESS" \
    -d "{\"tenantId\":\"$T2\",\"refreshToken\":\"$OTHER_REFRESH\"}")
if [ "$status" -eq 401 ]; then pass "Switch rejected when refresh token belongs to a different user (401)"; else fail "Expected 401, got $status"; fi
echo ""

echo "--- Test 8: No token → 401 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/switch-tenant" \
    -H "Content-Type: application/json" -d "{\"tenantId\":\"$T2\",\"refreshToken\":\"$NEWEST_REFRESH\"}")
if [ "$status" -eq 401 ]; then pass "Unauthenticated switch-tenant call rejected (401)"; else fail "Expected 401, got $status"; fi
echo ""

echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
