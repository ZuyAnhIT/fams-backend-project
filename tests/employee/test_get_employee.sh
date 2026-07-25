#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/employees/{employeeId} (get employee detail)
# Usage: BASE_URL=http://localhost:8080 bash test_get_employee.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

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

echo "=== Get Employee Detail Tests ==="
echo "Target: $BASE_URL"
echo ""

# Setup: login as platform admin
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then
    echo "SETUP FAILED: Could not login (HTTP $login_status)"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."
echo ""

# Setup: create tenant
echo "--- Setup: Create test tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Get Emp Corp\",\"slug\":\"get-emp-${TS}\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: id=$TENANT_ID"
echo ""

# Setup: create one employee (no linked user)
echo "--- Setup: Create employee via API ---"
emp_resp=$(curl -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Diana","lastName":"Prince","email":"diana@corp.com","employeeCode":"EMP-D01","position":"Field Engineer","department":"Engineering"}')
EMP_ID=$(echo "$emp_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$EMP_ID" ]; then
    echo "SETUP FAILED: Could not create employee"
    exit 1
fi
echo "Employee created: id=$EMP_ID"
echo ""

# Setup: seed an employee whose user has accepted an invitation (linked user with role)
echo "--- Setup: Create invited employee with role ---"
INV_EMAIL="linked.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INV_EMAIL\"}")
inv_status=$(echo "$inv_resp" | tail -n 1)
if [ "$inv_status" -ne 201 ]; then
    echo "SETUP WARNING: Could not send invitation (HTTP $inv_status) — skipping linked-user test"
    LINKED_EMP_ID=""
else
    INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT token FROM employee_invitations WHERE email='$INV_EMAIL' AND status='pending' LIMIT 1;" \
        | grep -oE '[0-9a-f-]{36}' | head -1)
    accept_resp=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/v1/invitations/accept" \
        -H "Content-Type: application/json" \
        -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Pass@linked1\",\"displayName\":\"Linked Emp\"}")
    accept_body=$(echo "$accept_resp" | head -n -1)
    accept_status=$(echo "$accept_resp" | tail -n 1)
    if [ "$accept_status" -eq 200 ]; then
        # Decode the JWT sub claim to get user_id — avoids a direct DB query
        accept_token=$(echo "$accept_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
        jwt_payload=$(echo "$accept_token" | cut -d'.' -f2 | base64 -d 2>/dev/null || true)
        USER_ID=$(echo "$jwt_payload" | grep -o '"sub":"[^"]*"' | cut -d'"' -f4 || true)
        LINKED_EMP_ID=$(docker exec fams-postgres psql -U "${DB_USER:-fams_user}" -d "${DB_NAME:-fams_db}" -t -c \
            "INSERT INTO employees (tenant_id, user_id, first_name, last_name, email, status)
             VALUES ('$TENANT_ID','$USER_ID','Linked','Emp','$INV_EMAIL','active')
             RETURNING id;" 2>/dev/null | grep -oE '[0-9a-f-]{36}' | head -1 || true)
        echo "Linked employee seeded: id=$LINKED_EMP_ID userId=$USER_ID"
    else
        LINKED_EMP_ID=""
        echo "SETUP WARNING: Could not accept invitation (HTTP $accept_status)"
    fi
fi
echo ""

BASE_EMP_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees"

# Test 1: Happy path — get employee without linked user
echo "--- Test 1: Happy path (employee without user) ---"
detail_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_EMP_URL/$EMP_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
detail_body=$(echo "$detail_resp" | head -n -1)
detail_status=$(echo "$detail_resp" | tail -n 1)
if [ "$detail_status" -eq 200 ]; then
    first_name=$(echo "$detail_body" | grep -o '"firstName":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    roles_present=$(echo "$detail_body" | grep -o '"roles":\[\]' || true)
    if [ "$first_name" = "Diana" ] && [ -n "$roles_present" ]; then
        echo "PASS: Happy path (HTTP 200, firstName=Diana, roles=[])"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Happy path — firstName='$first_name', roles_present='$roles_present'"
        echo "Body: $detail_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Happy path — expected HTTP 200, got HTTP $detail_status"
    echo "Body: $detail_body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Employee with linked user — roles should be populated
echo ""
echo "--- Test 2: Employee with linked user (roles populated) ---"
if [ -n "$LINKED_EMP_ID" ]; then
    linked_resp=$(curl -s -w "\n%{http_code}" \
        -X GET "$BASE_EMP_URL/$LINKED_EMP_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN")
    linked_body=$(echo "$linked_resp" | head -n -1)
    linked_status=$(echo "$linked_resp" | tail -n 1)
    if [ "$linked_status" -eq 200 ]; then
        has_roles=$(echo "$linked_body" | grep -o '"roleName":"[^"]*"' | head -1 || true)
        if [ -n "$has_roles" ]; then
            echo "PASS: Linked employee has roles (HTTP 200, $has_roles)"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Linked employee — HTTP 200 but no roles found"
            echo "Body: $linked_body"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Linked employee — expected HTTP 200, got HTTP $linked_status"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: No linked employee available"
fi

# Test 3: Employee not found → 404
echo ""
echo "--- Test 3: Employee not found ---"
run_test "Employee not found" 404 \
    -X GET "$BASE_EMP_URL/00000000-0000-0000-0000-000000000000" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 4: Cross-tenant — employee belongs to different tenant → 404
echo ""
echo "--- Test 4: Cross-tenant access ---"
other_t=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Other Corp\",\"slug\":\"other-get-emp-${TS}\"}")
OTHER_TENANT=$(echo "$other_t" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "Cross-tenant employee not found" 404 \
    -X GET "$BASE_URL/api/v1/tenants/$OTHER_TENANT/employees/$EMP_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 5: Unauthenticated → 401
echo ""
echo "--- Test 5: Unauthenticated ---"
run_test "Unauthenticated" 401 \
    -X GET "$BASE_EMP_URL/$EMP_ID"

# Test 6: Tenant not found → 404
echo ""
echo "--- Test 6: Tenant not found ---"
run_test "Tenant not found" 404 \
    -X GET "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/employees/$EMP_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 7: User without employees:read permission → 403
echo ""
echo "--- Test 7: Forbidden (no employees:read permission) ---"
reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"noperm.getmp.${TS}@fams.com\",\"password\":\"Regular@1234\",\"displayName\":\"No Perm\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
reg_body=$(echo "$reg_resp" | head -n -1)
if [ "$reg_status" -eq 201 ]; then
    NO_PERM_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
elif [ "$reg_status" -eq 409 ]; then
    l2=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"noperm.getmp.${TS}@fams.com\",\"password\":\"Regular@1234\"}")
    NO_PERM_TOKEN=$(echo "$l2" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
else
    NO_PERM_TOKEN=""
fi
if [ -n "$NO_PERM_TOKEN" ]; then
    run_test "No permission forbidden" 403 \
        -X GET "$BASE_EMP_URL/$EMP_ID" \
        -H "Authorization: Bearer $NO_PERM_TOKEN"
else
    echo "SKIP: Could not obtain unprivileged token (email verification required)"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
