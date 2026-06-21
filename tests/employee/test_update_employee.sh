#!/usr/bin/env bash
# Tests for PATCH /api/v1/tenants/{tenantId}/employees/{employeeId} (update employee)
# Usage: BASE_URL=http://localhost:8080 bash test_update_employee.sh

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

echo "=== Update Employee Tests ==="
echo "Target: $BASE_URL"
echo ""

# Setup: login as platform admin
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
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
    -d "{\"name\":\"Update Emp Corp\",\"slug\":\"upd-emp-${TS}\"}")
t_body=$(echo "$t_resp" | head -n -1)
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: id=$TENANT_ID"
echo ""

EMP_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees"

# Setup: create two employees
echo "--- Setup: Create employees ---"
e1_resp=$(curl -s -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Alice\",\"lastName\":\"Old\",\"employeeCode\":\"E-A-${TS}\",\"position\":\"Engineer\",\"department\":\"Construction\"}")
EMP1_ID=$(echo "$e1_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

e2_resp=$(curl -s -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Bob\",\"lastName\":\"Other\",\"employeeCode\":\"E-B-${TS}\"}")
EMP2_ID=$(echo "$e2_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Employees created: id1=$EMP1_ID id2=$EMP2_ID"
echo ""

# Test 1: Happy path — update multiple fields
echo "--- Test 1: Happy path (update position, department, email) ---"
upd_resp=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$EMP_URL/$EMP1_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"position":"Senior Engineer","department":"Operations","email":"alice.new@corp.com"}')
upd_body=$(echo "$upd_resp" | head -n -1)
upd_status=$(echo "$upd_resp" | tail -n 1)
if [ "$upd_status" -eq 200 ]; then
    pos=$(echo "$upd_body" | grep -o '"position":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    dept=$(echo "$upd_body" | grep -o '"department":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ "$pos" = "Senior Engineer" ] && [ "$dept" = "Operations" ]; then
        echo "PASS: Happy path (HTTP 200, position='$pos', department='$dept')"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Happy path — position='$pos', department='$dept'"
        echo "Body: $upd_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Happy path — expected HTTP 200, got HTTP $upd_status"
    echo "Body: $upd_body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Partial update — only lastName changes, other fields stay
echo ""
echo "--- Test 2: Partial update (lastName only) ---"
partial_resp=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$EMP_URL/$EMP1_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"lastName":"Updated"}')
partial_body=$(echo "$partial_resp" | head -n -1)
partial_status=$(echo "$partial_resp" | tail -n 1)
if [ "$partial_status" -eq 200 ]; then
    last=$(echo "$partial_body" | grep -o '"lastName":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    pos2=$(echo "$partial_body" | grep -o '"position":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ "$last" = "Updated" ] && [ "$pos2" = "Senior Engineer" ]; then
        echo "PASS: Partial update (HTTP 200, lastName=Updated, position retained)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Partial update — lastName='$last', position='$pos2'"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Partial update — expected HTTP 200, got HTTP $partial_status"
    FAIL=$((FAIL + 1))
fi

# Test 3: Update employeeCode to an already-used code → 409
echo ""
echo "--- Test 3: Duplicate employee code ---"
run_test "Duplicate employee code" 409 \
    -X PATCH "$EMP_URL/$EMP1_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeCode\":\"E-B-${TS}\"}"

# Test 4: Update to same employeeCode (no conflict with self) → 200
echo ""
echo "--- Test 4: Update to own existing code (no conflict) ---"
run_test "Same code no conflict" 200 \
    -X PATCH "$EMP_URL/$EMP1_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeCode\":\"E-A-${TS}\"}"

# Test 5: Invalid email → 400
echo ""
echo "--- Test 5: Invalid email ---"
run_test "Invalid email" 400 \
    -X PATCH "$EMP_URL/$EMP1_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"email":"not-an-email"}'

# Test 6: Invalid employee code characters → 400
echo ""
echo "--- Test 6: Invalid employee code ---"
run_test "Invalid employee code" 400 \
    -X PATCH "$EMP_URL/$EMP1_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"employeeCode":"BAD CODE!"}'

# Test 7: Employee not found → 404
echo ""
echo "--- Test 7: Employee not found ---"
run_test "Employee not found" 404 \
    -X PATCH "$EMP_URL/00000000-0000-0000-0000-000000000000" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Ghost"}'

# Test 8: Cross-tenant employee → 404
echo ""
echo "--- Test 8: Cross-tenant employee ---"
other_t=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Other\",\"slug\":\"other-upd-${TS}\"}")
OTHER_TENANT=$(echo "$other_t" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "Cross-tenant employee not found" 404 \
    -X PATCH "$BASE_URL/api/v1/tenants/$OTHER_TENANT/employees/$EMP1_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Cross"}'

# Test 9: Unauthenticated → 401
echo ""
echo "--- Test 9: Unauthenticated ---"
run_test "Unauthenticated" 401 \
    -X PATCH "$EMP_URL/$EMP1_ID" \
    -H "Content-Type: application/json" \
    -d '{"firstName":"Ghost"}'

# Test 10: No employees:update permission → 403
echo ""
echo "--- Test 10: Forbidden ---"
reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"noperm.updemp.${TS}@fams.com\",\"password\":\"Regular@1234\",\"displayName\":\"No Perm\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
reg_body=$(echo "$reg_resp" | head -n -1)
if [ "$reg_status" -eq 201 ]; then
    NO_PERM_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
elif [ "$reg_status" -eq 409 ]; then
    l2=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"noperm.updemp.${TS}@fams.com\",\"password\":\"Regular@1234\"}")
    NO_PERM_TOKEN=$(echo "$l2" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
else
    NO_PERM_TOKEN=""
fi
if [ -n "$NO_PERM_TOKEN" ]; then
    run_test "No permission forbidden" 403 \
        -X PATCH "$EMP_URL/$EMP1_ID" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $NO_PERM_TOKEN" \
        -d '{"firstName":"Forbidden"}'
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
