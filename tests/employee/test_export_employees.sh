#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/employees/export
# Usage: BASE_URL=http://localhost:8080 bash test_export_employees.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

echo "=== Export Employees Tests ==="
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

# Setup: create tenant and seed employees
echo "--- Setup: Create test tenant and employees ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Export Corp\",\"slug\":\"export-corp-${TS}\"}")
t_body=$(echo "$t_resp" | head -n -1)
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
EMP_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees"

curl -s -X POST "$EMP_URL" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Alice\",\"lastName\":\"Export\",\"employeeCode\":\"EXP-001\",\"department\":\"Engineering\",\"status\":\"active\"}" > /dev/null
curl -s -X POST "$EMP_URL" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Bob\",\"lastName\":\"Export\",\"employeeCode\":\"EXP-002\",\"department\":\"HR\"}" > /dev/null

# Change Bob to inactive
bob_id=$(curl -s "$EMP_URL?search=Bob" -H "Authorization: Bearer $ADMIN_TOKEN" \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
if [ -n "$bob_id" ]; then
    curl -s -X PATCH "$EMP_URL/$bob_id/status" -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"status":"inactive"}' > /dev/null
fi
echo "Tenant and employees created: id=$TENANT_ID"
echo ""

# Test 1: Export all employees — response is xlsx binary (check Content-Type and non-empty)
echo "--- Test 1: Export all employees ---"
resp_headers=$(curl -s -D - -o /tmp/export_all.xlsx \
    -w "%{http_code}" \
    "$EMP_URL/export" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
http_code=$(echo "$resp_headers" | tail -n 1)
content_type=$(echo "$resp_headers" | grep -i "content-type:" | head -1 | tr -d '\r' || true)
file_size=$(wc -c < /tmp/export_all.xlsx || echo 0)
if [ "$http_code" -eq 200 ] && echo "$content_type" | grep -qi "spreadsheetml" && [ "$file_size" -gt 100 ]; then
    echo "PASS: Export all (HTTP 200, xlsx, ${file_size} bytes)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Export all — HTTP=$http_code content-type='$content_type' size=$file_size"
    FAIL=$((FAIL + 1))
fi

# Test 2: Export with status filter (active only)
echo ""
echo "--- Test 2: Export with status=active filter ---"
resp_headers=$(curl -s -D - -o /tmp/export_active.xlsx \
    -w "%{http_code}" \
    "$EMP_URL/export?status=active" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
http_code=$(echo "$resp_headers" | tail -n 1)
file_size=$(wc -c < /tmp/export_active.xlsx || echo 0)
if [ "$http_code" -eq 200 ] && [ "$file_size" -gt 100 ]; then
    echo "PASS: Export active (HTTP 200, ${file_size} bytes)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Export active — HTTP=$http_code size=$file_size"
    FAIL=$((FAIL + 1))
fi

# Test 3: Export with department filter
echo ""
echo "--- Test 3: Export with department filter ---"
resp_headers=$(curl -s -D - -o /tmp/export_dept.xlsx \
    -w "%{http_code}" \
    "$EMP_URL/export?department=Engineering" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
http_code=$(echo "$resp_headers" | tail -n 1)
file_size=$(wc -c < /tmp/export_dept.xlsx || echo 0)
if [ "$http_code" -eq 200 ] && [ "$file_size" -gt 100 ]; then
    echo "PASS: Export by department (HTTP 200, ${file_size} bytes)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Export by department — HTTP=$http_code size=$file_size"
    FAIL=$((FAIL + 1))
fi

# Test 4: Export with search term
echo ""
echo "--- Test 4: Export with search filter ---"
http_code=$(curl -s -o /tmp/export_search.xlsx -w "%{http_code}" \
    "$EMP_URL/export?search=Alice" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
file_size=$(wc -c < /tmp/export_search.xlsx || echo 0)
if [ "$http_code" -eq 200 ] && [ "$file_size" -gt 100 ]; then
    echo "PASS: Export with search (HTTP 200, ${file_size} bytes)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Export with search — HTTP=$http_code size=$file_size"
    FAIL=$((FAIL + 1))
fi

# Test 5: Check Content-Disposition header has filename
echo ""
echo "--- Test 5: Content-Disposition filename ---"
resp_headers=$(curl -s -D - -o /dev/null \
    "$EMP_URL/export" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
disposition=$(echo "$resp_headers" | grep -i "content-disposition:" | tr -d '\r' || true)
if echo "$disposition" | grep -qi "employees.xlsx"; then
    echo "PASS: Content-Disposition contains employees.xlsx"
    PASS=$((PASS + 1))
else
    echo "FAIL: Content-Disposition missing or wrong: '$disposition'"
    FAIL=$((FAIL + 1))
fi

# Test 6: Tenant not found → 404
echo ""
echo "--- Test 6: Tenant not found ---"
http_code=$(curl -s -o /dev/null -w "%{http_code}" \
    "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/employees/export" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
if [ "$http_code" -eq 404 ]; then
    echo "PASS: Tenant not found (HTTP 404)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Tenant not found — expected 404, got $http_code"
    FAIL=$((FAIL + 1))
fi

# Test 7: Unauthenticated → 401
echo ""
echo "--- Test 7: Unauthenticated ---"
http_code=$(curl -s -o /dev/null -w "%{http_code}" "$EMP_URL/export")
if [ "$http_code" -eq 401 ]; then
    echo "PASS: Unauthenticated (HTTP 401)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Unauthenticated — expected 401, got $http_code"
    FAIL=$((FAIL + 1))
fi

# Test 8: No permission → 403
echo ""
echo "--- Test 8: Forbidden ---"
reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"noperm.export.${TS}@fams.com\",\"password\":\"Regular@1234\",\"displayName\":\"No Perm\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
reg_body=$(echo "$reg_resp" | head -n -1)
if [ "$reg_status" -eq 201 ]; then
    NO_PERM_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
elif [ "$reg_status" -eq 409 ]; then
    l2=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"noperm.export.${TS}@fams.com\",\"password\":\"Regular@1234\"}")
    NO_PERM_TOKEN=$(echo "$l2" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
else
    NO_PERM_TOKEN=""
fi
if [ -n "$NO_PERM_TOKEN" ]; then
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        "$EMP_URL/export" -H "Authorization: Bearer $NO_PERM_TOKEN")
    if [ "$http_code" -eq 403 ]; then
        echo "PASS: No permission forbidden (HTTP 403)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: No permission — expected 403, got $http_code"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Could not obtain unprivileged token (email verification required)"
fi

# Cleanup tmp files
rm -f /tmp/export_all.xlsx /tmp/export_active.xlsx /tmp/export_dept.xlsx /tmp/export_search.xlsx

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
