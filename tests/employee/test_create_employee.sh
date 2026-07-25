#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/employees (create employee manually)
# Usage: BASE_URL=http://localhost:8080 bash test_create_employee.sh

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

echo "=== Create Employee (Manual) Tests ==="
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
    -d "{\"name\":\"Create Emp Corp\",\"slug\":\"create-emp-${TS}\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: id=$TENANT_ID"
echo ""

EMP_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees"

# Test 1: Happy path — full data
echo "--- Test 1: Happy path (all fields) ---"
create_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{
      \"firstName\": \"John\",
      \"lastName\": \"Doe\",
      \"email\": \"john.doe.${TS}@corp.com\",
      \"phone\": \"+84901234567\",
      \"employeeCode\": \"EMP-${TS}\",
      \"position\": \"Site Engineer\",
      \"department\": \"Construction\",
      \"hiredDate\": \"2024-01-15\"
    }")
create_body=$(echo "$create_resp" | head -n -1)
create_status=$(echo "$create_resp" | tail -n 1)
if [ "$create_status" -eq 201 ]; then
    emp_id=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    status_field=$(echo "$create_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ -n "$emp_id" ] && [ "$status_field" = "active" ]; then
        echo "PASS: Happy path (HTTP 201, id=$emp_id, status=active)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Happy path — id='$emp_id', status='$status_field'"
        echo "Body: $create_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Happy path — expected HTTP 201, got HTTP $create_status"
    echo "Body: $create_body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Minimal required fields only (firstName + lastName)
echo ""
echo "--- Test 2: Minimal fields (firstName + lastName only) ---"
min_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Jane","lastName":"Smith"}')
min_body=$(echo "$min_resp" | head -n -1)
min_status=$(echo "$min_resp" | tail -n 1)
if [ "$min_status" -eq 201 ]; then
    emp_id2=$(echo "$min_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    echo "PASS: Minimal fields (HTTP 201, id=$emp_id2)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Minimal fields — expected HTTP 201, got HTTP $min_status"
    echo "Body: $min_body"
    FAIL=$((FAIL + 1))
fi

# Test 3: Duplicate employee code → 409
echo ""
echo "--- Test 3: Duplicate employee code ---"
run_test "Duplicate employee code" 409 \
    -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Dup\",\"lastName\":\"Code\",\"employeeCode\":\"EMP-${TS}\"}"

# Test 4: Missing firstName → 400
echo ""
echo "--- Test 4: Missing firstName ---"
run_test "Missing firstName" 400 \
    -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"lastName":"Doe"}'

# Test 5: Missing lastName → 400
echo ""
echo "--- Test 5: Missing lastName ---"
run_test "Missing lastName" 400 \
    -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"John"}'

# Test 6: Invalid email format → 400
echo ""
echo "--- Test 6: Invalid email ---"
run_test "Invalid email" 400 \
    -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Bad","lastName":"Email","email":"not-an-email"}'

# Test 7: Invalid employee code characters → 400
echo ""
echo "--- Test 7: Invalid employee code characters ---"
run_test "Invalid employee code" 400 \
    -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Bad","lastName":"Code","employeeCode":"EMP 001 !"}'

# Test 8: Unauthenticated → 401
echo ""
echo "--- Test 8: Unauthenticated ---"
run_test "Unauthenticated" 401 \
    -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -d '{"firstName":"Ghost","lastName":"User"}'

# Test 9: Tenant not found → 404
echo ""
echo "--- Test 9: Tenant not found ---"
run_test "Tenant not found" 404 \
    -X POST "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/employees" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Ghost","lastName":"Tenant"}'

# Test 10: User without employees:create permission → 403
echo ""
echo "--- Test 10: Forbidden (no employees:create permission) ---"
reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"noperm.createmp.${TS}@fams.com\",\"password\":\"Regular@1234\",\"displayName\":\"No Perm\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
reg_body=$(echo "$reg_resp" | head -n -1)
if [ "$reg_status" -eq 201 ]; then
    NO_PERM_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
elif [ "$reg_status" -eq 409 ]; then
    l2=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"noperm.createmp.${TS}@fams.com\",\"password\":\"Regular@1234\"}")
    NO_PERM_TOKEN=$(echo "$l2" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
else
    NO_PERM_TOKEN=""
fi
if [ -n "$NO_PERM_TOKEN" ]; then
    run_test "No permission forbidden" 403 \
        -X POST "$EMP_URL" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $NO_PERM_TOKEN" \
        -d '{"firstName":"Forbidden","lastName":"User"}'
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
