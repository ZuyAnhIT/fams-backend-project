#!/usr/bin/env bash
# Tests for GET /api/v1/roles/{id}
# Usage: BASE_URL=http://localhost:8080 bash test_get_role.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1 — $2"; FAIL=$((FAIL + 1)); }

echo "=== Get Role by ID Tests ==="
echo "Target: $BASE_URL"
echo ""

# ─── Setup: Obtain admin token ───────────────────────────────────────────────
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
[ "$login_status" -ne 200 ] && echo "FATAL: admin login failed (HTTP $login_status)" && exit 1
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | sed 's/"accessToken":"//;s/"//')
echo "Admin token obtained."

# ─── Setup: Create a tenant + custom role ────────────────────────────────────
TS=$(date +%s)
echo ""
echo "--- Setup: Create tenant + custom role ---"
tenant_resp=$(curl -s \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Role Detail Corp\",\"slug\":\"role-detail-$TS\"}")
TENANT_ID=$(echo "$tenant_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$TENANT_ID" ] && echo "FATAL: could not create tenant" && exit 1
echo "Tenant created: id=$TENANT_ID"

# Get a system role ID from the list
list_resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/roles?isSystem=true&size=1" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
SYSTEM_ROLE_ID=$(echo "$list_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$SYSTEM_ROLE_ID" ] && echo "FATAL: no system role found" && exit 1
echo "System role id: $SYSTEM_ROLE_ID"

# Create a custom role in the tenant
role_resp=$(curl -s \
    -X POST "$BASE_URL/api/v1/roles" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"custom-role-$TS\",\"description\":\"Test role\"}")
CUSTOM_ROLE_ID=$(echo "$role_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$CUSTOM_ROLE_ID" ] && echo "FATAL: could not create custom role" && exit 1
echo "Custom role created: id=$CUSTOM_ROLE_ID"

# Register a regular user (no tenant membership)
REGULAR_TOKEN=$(register_verified_test_user_token "$BASE_URL" "RegUser")
[ -z "$REGULAR_TOKEN" ] && echo "FATAL: could not register regular user" && exit 1
echo "Regular user token obtained."
echo ""

# ─── Test 1: Get system role — happy path, admin ─────────────────────────────
echo "--- Test 1: Get system role (admin) ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles/$SYSTEM_ROLE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 200 ]; then
    role_id=$(echo "$body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    has_permissions=$(echo "$body" | grep -o '"permissions":\[' | head -1)
    has_permission_count=$(echo "$body" | grep -o '"permissionCount":[0-9]*' | head -1)
    if [ "$role_id" = "$SYSTEM_ROLE_ID" ] && [ -n "$has_permissions" ] && [ -n "$has_permission_count" ]; then
        pass "Get system role — HTTP 200, permissions array present, permissionCount present"
    else
        fail "Get system role" "id=$role_id has_permissions='$has_permissions' has_permission_count='$has_permission_count'"
        echo "Body: $body"
    fi
else
    fail "Get system role" "expected HTTP 200, got HTTP $status"
    echo "Body: $body"
fi

# ─── Test 2: Get system role — any authenticated user can view ───────────────
echo ""
echo "--- Test 2: Get system role (regular user, no tenant membership) ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles/$SYSTEM_ROLE_ID" \
    -H "Authorization: Bearer $REGULAR_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 200 ]; then
    is_system=$(echo "$body" | grep -o '"system":[a-z]*' | head -1 | cut -d: -f2)
    if [ "$is_system" = "true" ]; then
        pass "Any user can view system role — HTTP 200, system=true"
    else
        fail "System role check" "system=$is_system, expected true"
    fi
else
    fail "Regular user view system role" "expected HTTP 200, got HTTP $status"
fi

# ─── Test 3: Get custom role — admin can view any tenant's role ──────────────
echo ""
echo "--- Test 3: Get custom role (platform admin) ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles/$CUSTOM_ROLE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 200 ]; then
    role_id=$(echo "$body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    tenant_id=$(echo "$body" | grep -o '"tenantId":"[^"]*"' | head -1 | cut -d'"' -f4)
    has_permissions=$(echo "$body" | grep -o '"permissions":\[' | head -1)
    if [ "$role_id" = "$CUSTOM_ROLE_ID" ] && [ "$tenant_id" = "$TENANT_ID" ] && [ -n "$has_permissions" ]; then
        pass "Platform admin can view custom role — HTTP 200"
    else
        fail "Get custom role (admin)" "role_id=$role_id tenant_id=$tenant_id has_permissions='$has_permissions'"
    fi
else
    fail "Get custom role (admin)" "expected HTTP 200, got HTTP $status"
    echo "Body: $body"
fi

# ─── Test 4: Get custom role — regular user without tenant membership → 403 ──
echo ""
echo "--- Test 4: Get custom role (regular user, no tenant membership) → 403 ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles/$CUSTOM_ROLE_ID" \
    -H "Authorization: Bearer $REGULAR_TOKEN")
status=$(echo "$resp" | tail -n 1)

if [ "$status" -eq 403 ]; then
    pass "Regular user cannot view other tenant's custom role — HTTP 403"
else
    fail "Tenant isolation" "expected HTTP 403, got HTTP $status"
fi

# ─── Test 5: Permissions array populated for system role ─────────────────────
echo ""
echo "--- Test 5: Verify permissions array is an array (system role) ---"
resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/roles/$SYSTEM_ROLE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
perm_count_val=$(echo "$resp" | grep -o '"permissionCount":[0-9]*' | grep -o '[0-9]*' | head -1)
permissions_arr=$(echo "$resp" | grep -o '"permissions":\[[^]]*\]' | head -1)

if [ -n "$perm_count_val" ]; then
    pass "permissionCount=$perm_count_val present in RoleDetailResponse"
else
    fail "permissionCount missing" "not found in response"
fi

# ─── Test 6: Non-existent role → 404 ─────────────────────────────────────────
echo ""
echo "--- Test 6: Non-existent role → 404 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles/00000000-0000-0000-0000-000000000000" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
if [ "$status" -eq 404 ]; then
    pass "Non-existent role — HTTP 404"
else
    fail "Non-existent role" "expected HTTP 404, got HTTP $status"
fi

# ─── Test 7: Unauthenticated → 401 ───────────────────────────────────────────
echo ""
echo "--- Test 7: Unauthenticated → 401 ---"
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "$BASE_URL/api/v1/roles/$SYSTEM_ROLE_ID")
if [ "$status" -eq 401 ]; then
    pass "Unauthenticated rejected — HTTP 401"
else
    fail "Unauthenticated" "expected HTTP 401, got HTTP $status"
fi

# ─── Summary ─────────────────────────────────────────────────────────────────
echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
[ "$FAIL" -eq 0 ]
