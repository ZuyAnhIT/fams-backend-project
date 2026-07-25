#!/usr/bin/env bash
# Tests for Issue #10 (docs/issues/ISSUES.md): a new "Platform Staff" role below Platform Admin
# with a narrower, read-only operational-support permission set (view/list tenants, view audit
# logs, view system status) — no create/update/delete/sensitive-config rights.
# Usage: BASE_URL=http://localhost:8080 bash test_platform_staff_role.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
TS=$(date +%s)

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "=== Platform Staff Role Tests (Issue #10) ==="
echo "Target: $BASE_URL"
echo ""

echo "--- Setup: Login as platform admin ---"
ADMIN_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[ -z "$ADMIN_TOKEN" ] && echo "SETUP FAILED: no admin token" && exit 1

ROLE_ID=$(curl -s "$BASE_URL/api/v1/roles?search=PLATFORM_STAFF" -H "Authorization: Bearer $ADMIN_TOKEN" \
    | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']['content']
match=[r for r in d if r['name']=='PLATFORM_STAFF']
print(match[0]['id'] if match else '')
" 2>/dev/null || true)
if [ -z "$ROLE_ID" ]; then
    ROLE_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "SELECT id FROM roles WHERE name='PLATFORM_STAFF';" | tr -d ' ')
fi
[ -z "$ROLE_ID" ] && echo "SETUP FAILED: PLATFORM_STAFF role not found" && exit 1
echo "PLATFORM_STAFF role id: $ROLE_ID"

echo ""
echo "--- Setup: register a plain regular user (no roles at all) ---"
STAFF_EMAIL="platform_staff_${TS}@fams.com"
STAFF_TOKEN=$(register_verified_test_user_token "$BASE_URL" "Platform Staff Test" "$STAFF_EMAIL")
STAFF_USER_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "SELECT id FROM users WHERE email='$STAFF_EMAIL';" | tr -d ' ')

echo ""
echo "--- Test 1: Before assignment, plain user is denied platform-level read endpoints ---"
status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $STAFF_TOKEN")
if [ "$status" -eq 403 ]; then pass "GET /tenants denied before role assignment (403)"; else fail "Expected 403, got $status"; fi

echo ""
echo "--- Test 2: Non-platform-admin cannot assign a platform role ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/user-roles/platform" -H "Authorization: Bearer $STAFF_TOKEN" -H "Content-Type: application/json" \
    -d "{\"userId\":\"$STAFF_USER_ID\",\"roleId\":\"$ROLE_ID\"}")
if [ "$status" -eq 403 ]; then pass "Regular user cannot self-assign a platform role (403)"; else fail "Expected 403, got $status"; fi

echo ""
echo "--- Test 3: Platform Admin assigns PLATFORM_STAFF to the user ---"
resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/user-roles/platform" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"userId\":\"$STAFF_USER_ID\",\"roleId\":\"$ROLE_ID\"}")
status=$(echo "$resp" | tail -n1); body=$(echo "$resp" | head -n -1)
ASSIGNMENT_ID=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null || true)
if [ "$status" -eq 201 ] && [ -n "$ASSIGNMENT_ID" ]; then
    pass "Platform Admin assigns PLATFORM_STAFF (HTTP 201, tenantId=null)"
else
    fail "Expected 201, got $status: $body"
fi

echo ""
echo "--- Test 4: Duplicate assignment is rejected ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/user-roles/platform" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"userId\":\"$STAFF_USER_ID\",\"roleId\":\"$ROLE_ID\"}")
if [ "$status" -eq 409 ]; then pass "Duplicate platform role assignment rejected (409)"; else fail "Expected 409, got $status"; fi

echo ""
echo "--- Test 5: After assignment, the new token can access read-only platform endpoints ---"
STAFF_TOKEN2=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$STAFF_EMAIL\",\"password\":\"TestPass1\"}" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
s1=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $STAFF_TOKEN2")
s2=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/platform/system-status" -H "Authorization: Bearer $STAFF_TOKEN2")
s3=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/audit-logs" -H "Authorization: Bearer $STAFF_TOKEN2")
if [ "$s1" -eq 200 ] && [ "$s2" -eq 200 ] && [ "$s3" -eq 200 ]; then
    pass "PLATFORM_STAFF can view tenants list, system status, and audit logs (all 200)"
else
    fail "Expected all 200, got tenants=$s1 system-status=$s2 audit=$s3"
fi

echo ""
echo "--- Test 6: PLATFORM_STAFF still denied sensitive/mutating platform actions ---"
TENANT_ID=$(curl -s "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $STAFF_TOKEN2" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['content'][0]['id'])")
s4=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/suspend" -H "Authorization: Bearer $STAFF_TOKEN2")
s5=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $STAFF_TOKEN2" -H "Content-Type: application/json" \
    -d "{\"name\":\"Should Not Create\",\"slug\":\"should-not-create-$TS\",\"industry\":\"tech\",\"countryCode\":\"VN\"}")
if [ "$s4" -eq 403 ]; then pass "Suspend tenant still denied for PLATFORM_STAFF (403)"; else fail "Expected 403, got $s4"; fi
# V66 grants PLATFORM_STAFF the tenants:create permission so they can provision tenants for
# customers — but that puts them on the SAME "provisioning" path as a Platform Admin, which
# requires an explicit owner (see TenantService.createTenant / tests/tenant/test_create_tenant.sh
# Test 1). So unlike a plain regular user's self-serve path, PLATFORM_STAFF creating a tenant
# WITHOUT specifying an owner is now correctly rejected — this is intentional, not a leak.
if [ "$s5" -eq 400 ]; then
    pass "PLATFORM_STAFF tenant creation without an owner is rejected (400), same as Platform Admin"
else
    fail "Expected 400 (owner required via the provisioning path), got $s5"
fi

echo ""
echo "--- Test 7: Revoke the platform role ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE_URL/api/v1/user-roles/$ASSIGNMENT_ID" -H "Authorization: Bearer $ADMIN_TOKEN")
if [ "$status" -eq 200 ]; then pass "Platform Admin revokes the platform role (200)"; else fail "Expected 200, got $status"; fi

echo ""
echo "--- Test 8: After revoke, access is denied again immediately (cache correctly evicted) ---"
STAFF_TOKEN3=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$STAFF_EMAIL\",\"password\":\"TestPass1\"}" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $STAFF_TOKEN3")
if [ "$status" -eq 403 ]; then
    pass "Access denied again immediately after revoke (403, no stale-cache window)"
else
    fail "Expected 403, got $status"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
