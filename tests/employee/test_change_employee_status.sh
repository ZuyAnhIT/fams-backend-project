#!/usr/bin/env bash
# Tests for PATCH /api/v1/tenants/{tenantId}/employees/{employeeId}/status
# Usage: BASE_URL=http://localhost:8080 bash test_change_employee_status.sh

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

echo "=== Change Employee Status Tests ==="
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
    -d "{\"name\":\"Status Corp\",\"slug\":\"status-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
t_body=$(echo "$t_resp" | head -n -1)
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: id=$TENANT_ID"
echo ""

EMP_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees"

# Setup: create an employee
echo "--- Setup: Create employee ---"
e_resp=$(curl -s -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Alice\",\"lastName\":\"Active\",\"employeeCode\":\"E-ST-${TS}\"}")
EMP_ID=$(echo "$e_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Employee created: id=$EMP_ID"
echo ""

# Test 1: Set status to inactive
echo "--- Test 1: Set status to inactive ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$EMP_URL/$EMP_ID/status" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"inactive"}')
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)
if [ "$status" -eq 200 ]; then
    got=$(echo "$body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ "$got" = "inactive" ]; then
        echo "PASS: Set inactive (HTTP 200, status='$got')"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Set inactive — status='$got'"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Set inactive — expected HTTP 200, got HTTP $status"
    echo "Body: $body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Set status to terminated
echo ""
echo "--- Test 2: Set status to terminated ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$EMP_URL/$EMP_ID/status" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"terminated"}')
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)
if [ "$status" -eq 200 ]; then
    got=$(echo "$body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ "$got" = "terminated" ]; then
        echo "PASS: Set terminated (HTTP 200, status='$got')"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Set terminated — status='$got'"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Set terminated — expected HTTP 200, got HTTP $status"
    echo "Body: $body"
    FAIL=$((FAIL + 1))
fi

# Test 3: Set status back to active
echo ""
echo "--- Test 3: Set status back to active ---"
resp=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$EMP_URL/$EMP_ID/status" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"active"}')
body=$(echo "$resp" | head -n -1)
status=$(echo "$resp" | tail -n 1)
if [ "$status" -eq 200 ]; then
    got=$(echo "$body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ "$got" = "active" ]; then
        echo "PASS: Set active (HTTP 200, status='$got')"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Set active — status='$got'"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Set active — expected HTTP 200, got HTTP $status"
    FAIL=$((FAIL + 1))
fi

# Test 4: Invalid status value → 400
echo ""
echo "--- Test 4: Invalid status value ---"
run_test "Invalid status" 400 \
    -X PATCH "$EMP_URL/$EMP_ID/status" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"suspended"}'

# Test 5: Missing status field → 400
echo ""
echo "--- Test 5: Missing status field ---"
run_test "Missing status" 400 \
    -X PATCH "$EMP_URL/$EMP_ID/status" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{}'

# Test 6: Employee not found → 404
echo ""
echo "--- Test 6: Employee not found ---"
run_test "Employee not found" 404 \
    -X PATCH "$EMP_URL/00000000-0000-0000-0000-000000000000/status" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"inactive"}'

# Test 7: Cross-tenant employee → 404
echo ""
echo "--- Test 7: Cross-tenant employee ---"
other_t=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Other\",\"slug\":\"other-stat-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
OTHER_TENANT=$(echo "$other_t" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "Cross-tenant employee not found" 404 \
    -X PATCH "$BASE_URL/api/v1/tenants/$OTHER_TENANT/employees/$EMP_ID/status" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"inactive"}'

# Test 8: Unauthenticated → 401
echo ""
echo "--- Test 8: Unauthenticated ---"
run_test "Unauthenticated" 401 \
    -X PATCH "$EMP_URL/$EMP_ID/status" \
    -H "Content-Type: application/json" \
    -d '{"status":"inactive"}'

# Test 9: No permission → 403
echo ""
echo "--- Test 9: Forbidden ---"
reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"noperm.stat.${TS}@fams.com\",\"password\":\"Regular@1234\",\"displayName\":\"No Perm\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
reg_body=$(echo "$reg_resp" | head -n -1)
if [ "$reg_status" -eq 201 ]; then
    NO_PERM_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
elif [ "$reg_status" -eq 409 ]; then
    l2=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"identifier\":\"noperm.stat.${TS}@fams.com\",\"password\":\"Regular@1234\"}")
    NO_PERM_TOKEN=$(echo "$l2" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
else
    NO_PERM_TOKEN=""
fi
if [ -n "$NO_PERM_TOKEN" ]; then
    run_test "No permission forbidden" 403 \
        -X PATCH "$EMP_URL/$EMP_ID/status" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $NO_PERM_TOKEN" \
        -d '{"status":"inactive"}'
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
