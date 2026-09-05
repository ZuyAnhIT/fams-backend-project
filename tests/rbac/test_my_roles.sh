#!/usr/bin/env bash
# Tests for GET /api/v1/roles/me (current user's roles and permissions)
# Usage: BASE_URL=http://localhost:8080 bash test_my_roles.sh

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== GET /api/v1/roles/me Tests ==="
echo "Target: $BASE_URL"
echo ""

ME_URL="$BASE_URL/api/v1/roles/me"

# ─── Setup: Login as platform admin ──────────────────────────────────────────
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then
    echo "SETUP FAILED: Could not login as admin (HTTP $login_status)"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
ADMIN_USER_ID=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 | \
    cut -d'.' -f2 | tr '_-' '/+' | awk '{l=length($0)%4; if(l==2)$0=$0"=="; if(l==3)$0=$0"="; print}' | \
    base64 -d 2>/dev/null | grep -o '"sub":"[^"]*"' | cut -d'"' -f4 || true)
echo "Admin userId: $ADMIN_USER_ID"
echo ""

# ─── Test 1: Unauthenticated → 401 ───────────────────────────────────────────
echo "--- Test 1: Unauthenticated ---"
run_test "Unauthenticated" 401 -X GET "$ME_URL"

# ─── Test 2: Admin with no tenant roles → 200 with empty list ────────────────
echo ""
echo "--- Test 2: Admin with no tenant roles returns empty list ---"
me_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$ME_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
me_body=$(echo "$me_resp" | head -n -1)
me_status=$(echo "$me_resp" | tail -n 1)

if [ "$me_status" -eq 200 ]; then
    # data should be an array
    is_array=$(echo "$me_body" | grep -o '"data":\[' | head -1 || true)
    if [ -n "$is_array" ]; then
        echo "PASS: Returns 200 with data array"
        PASS=$((PASS + 1))
    else
        echo "FAIL: data field is not an array"
        echo "Body: $me_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: expected HTTP 200, got HTTP $me_status"
    echo "Body: $me_body"
    FAIL=$((FAIL + 1))
fi

# ─── Setup: Create a separate company owner ───────────────────────────────────
echo ""
echo "--- Setup: Create tenant with a non-platform owner ---"
TS=$(date +%s)
OWNER_EMAIL="myroles_${TS}_$$@example.com"
OWNER_TOKEN=$(register_verified_test_user_token "$BASE_URL" "My Roles Owner" "$OWNER_EMAIL")
OWNER_USER_ID=$(echo "$OWNER_TOKEN" | cut -d'.' -f2 | tr '_-' '/+' | awk '{l=length($0)%4; if(l==2)$0=$0"=="; if(l==3)$0=$0"="; print}' | \
    base64 -d 2>/dev/null | grep -o '"sub":"[^"]*"' | cut -d'"' -f4 || true)
if [ -z "$OWNER_TOKEN" ] || [ -z "$OWNER_USER_ID" ]; then
    echo "SETUP FAILED: Could not create regular company owner"
    exit 1
fi

t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"MyRoles Corp\",\"slug\":\"my-roles-$TS-$$\",\"ownerEmail\":\"$OWNER_EMAIL\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant: $TENANT_ID"

# Get a company role and verify that it cannot be assigned to Platform Admin.
roles_resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/roles?isSystem=true&search=TENANT_ADMIN" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
ROLE_ID=$(echo "$roles_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
ROLE_NAME="TENANT_ADMIN"

if [ -z "$ROLE_ID" ]; then
    # Fallback to EMPLOYEE role
    roles_resp=$(curl -s -X GET "$BASE_URL/api/v1/roles?isSystem=true&search=EMPLOYEE" \
        -H "Authorization: Bearer $ADMIN_TOKEN")
    ROLE_ID=$(echo "$roles_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    ROLE_NAME="EMPLOYEE"
fi

if [ -z "$ROLE_ID" ]; then
    echo "SETUP FAILED: Could not find any system role"
    exit 1
fi
echo "Using role: $ROLE_NAME ($ROLE_ID)"

assign_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/user-roles" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"userId\":\"$ADMIN_USER_ID\",\"roleId\":\"$ROLE_ID\",\"tenantId\":\"$TENANT_ID\"}")
assign_status=$(echo "$assign_resp" | tail -n 1)
if [ "$assign_status" -eq 400 ]; then
    echo "PASS: Platform Admin cannot receive a company role (HTTP 400)"
    PASS=$((PASS + 1))
else
    echo "FAIL: expected Platform Admin role assignment to return HTTP 400, got $assign_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ─── Test 3: After role assignment — returns the role with permissions ─────────
echo "--- Test 3: Response contains role assignment with permissions ---"
me2_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$ME_URL" \
    -H "Authorization: Bearer $OWNER_TOKEN")
me2_body=$(echo "$me2_resp" | head -n -1)
me2_status=$(echo "$me2_resp" | tail -n 1)

if [ "$me2_status" -eq 200 ]; then
    has_role=$(echo "$me2_body" | grep -o '"roleName":"[^"]*"' | head -1 || true)
    has_perms=$(echo "$me2_body" | grep -o '"permissions":\[' | head -1 || true)
    has_tenant=$(echo "$me2_body" | grep -o "\"tenantId\":\"$TENANT_ID\"" | head -1 || true)

    if [ -n "$has_role" ]; then
        echo "PASS: roleName present: $has_role"
        PASS=$((PASS + 1))
    else
        echo "FAIL: roleName missing from response"
        echo "Body: $me2_body"
        FAIL=$((FAIL + 1))
    fi

    if [ -n "$has_perms" ]; then
        echo "PASS: permissions array present"
        PASS=$((PASS + 1))
    else
        echo "FAIL: permissions array missing"
        FAIL=$((FAIL + 1))
    fi

    if [ -n "$has_tenant" ]; then
        echo "PASS: tenantId=$TENANT_ID present"
        PASS=$((PASS + 1))
    else
        echo "FAIL: expected tenantId=$TENANT_ID in response"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: expected HTTP 200, got HTTP $me2_status"
    echo "Body: $me2_body"
    FAIL=$((FAIL + 1))
fi

# ─── Test 4: Response shape — required fields present ─────────────────────────
echo ""
echo "--- Test 4: Response includes id, userId, roleId, assignedAt ---"
has_id=$(echo "$me2_body" | grep -o '"id":"[^"]*"' | head -1 || true)
has_user=$(echo "$me2_body" | grep -o '"userId":"[^"]*"' | head -1 || true)
has_role_id=$(echo "$me2_body" | grep -o '"roleId":"[^"]*"' | head -1 || true)
has_assigned=$(echo "$me2_body" | grep -o '"assignedAt":"[^"]*"' | head -1 || true)

if [ -n "$has_id" ]; then
    echo "PASS: id field present"
    PASS=$((PASS + 1))
else
    echo "FAIL: id field missing"
    FAIL=$((FAIL + 1))
fi
if [ -n "$has_user" ]; then
    echo "PASS: userId field present"
    PASS=$((PASS + 1))
else
    echo "FAIL: userId field missing"
    FAIL=$((FAIL + 1))
fi
if [ -n "$has_role_id" ]; then
    echo "PASS: roleId field present"
    PASS=$((PASS + 1))
else
    echo "FAIL: roleId field missing"
    FAIL=$((FAIL + 1))
fi
if [ -n "$has_assigned" ]; then
    echo "PASS: assignedAt field present"
    PASS=$((PASS + 1))
else
    echo "FAIL: assignedAt field missing"
    FAIL=$((FAIL + 1))
fi

# ─── Cleanup: archive the disposable tenant/user created by this test ──────────
echo ""
echo "--- Cleanup: Archive disposable fixtures ---"
docker exec fams-postgres psql -U fams_user -d fams_db -q \
    -c "UPDATE user_roles SET deleted_at = COALESCE(deleted_at, NOW()) WHERE tenant_id = '$TENANT_ID'::uuid;
     UPDATE tenants SET status = 'cancelled', deleted_at = COALESCE(deleted_at, NOW()) WHERE id = '$TENANT_ID'::uuid;
     UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, NOW()) WHERE user_id = '$OWNER_USER_ID'::uuid;
     UPDATE users SET is_active = false, deleted_at = COALESCE(deleted_at, NOW()) WHERE id = '$OWNER_USER_ID'::uuid;" \
    >/dev/null

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
