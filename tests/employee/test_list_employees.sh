#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/employees (list employees)
# Usage: BASE_URL=http://localhost:8080 bash test_list_employees.sh

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

echo "=== List Employees Tests ==="
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
    echo "SETUP FAILED: Could not login as admin (HTTP $login_status)"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."
echo ""

# Setup: create a tenant
echo "--- Setup: Create test tenant ---"
TS=$(date +%s)
SLUG="emp-list-test-${TS}"
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Emp List Corp\",\"slug\":\"$SLUG\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: id=$TENANT_ID"
echo ""

# Setup: create three employees via API for filtering/search tests
echo "--- Setup: Create test employees ---"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Alice","lastName":"Walker","email":"alice.walker@corp.com","employeeCode":"EMP-001","position":"Engineer","department":"Construction"}'
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Bob","lastName":"Smith","email":"bob.smith@corp.com","employeeCode":"EMP-002","position":"Supervisor","department":"Operations"}'
charlie_resp=$(curl -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Charlie","lastName":"Jones","email":"charlie.jones@corp.com","employeeCode":"EMP-003","position":"Technician","department":"Construction"}')
CHARLIE_ID=$(echo "$charlie_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -n "$CHARLIE_ID" ]; then
    curl -s -o /dev/null \
        -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/employees/$CHARLIE_ID/status" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d '{"status":"inactive"}'
fi
echo "Created 3 employees (Alice active, Bob active, Charlie inactive)."
echo ""

LIST_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees"

# Test 1: Happy path — list all employees (paginated)
echo "--- Test 1: Happy path (list all) ---"
list_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$LIST_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_body=$(echo "$list_resp" | head -n -1)
list_status=$(echo "$list_resp" | tail -n 1)
if [ "$list_status" -eq 200 ]; then
    total=$(echo "$list_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2 || true)
    if [ "${total:-0}" -ge 3 ]; then
        echo "PASS: Happy path (HTTP 200, totalElements=$total)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Happy path — HTTP 200 but totalElements=$total (expected ≥3)"
        echo "Body: $list_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Happy path — expected HTTP 200, got HTTP $list_status"
    echo "Body: $list_body"
    FAIL=$((FAIL + 1))
fi

# Test 1b: faceId field present and defaults to not_enrolled for new employees
echo ""
echo "--- Test 1b: faceId field present in list response ---"
faceid_resp=$(curl -s \
    -X GET "$LIST_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
face_status=$(echo "$faceid_resp" | grep -o '"status":"not_enrolled"' | head -1 || true)
if [ -n "$face_status" ]; then
    echo "PASS: faceId present and status=not_enrolled for new employees"
    PASS=$((PASS + 1))
else
    echo "FAIL: faceId field missing or status not 'not_enrolled' in list response"
    echo "Body: $faceid_resp"
    FAIL=$((FAIL + 1))
fi

# Test 2: Search by name — should find Alice
echo ""
echo "--- Test 2: Search by name ---"
search_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$LIST_URL?search=alice" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
search_body=$(echo "$search_resp" | head -n -1)
search_status=$(echo "$search_resp" | tail -n 1)
if [ "$search_status" -eq 200 ]; then
    total=$(echo "$search_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2 || true)
    if [ "${total:-0}" -eq 1 ]; then
        echo "PASS: Search by name (HTTP 200, totalElements=1)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Search by name — expected totalElements=1, got $total"
        echo "Body: $search_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Search by name — expected HTTP 200, got HTTP $search_status"
    FAIL=$((FAIL + 1))
fi

# Test 3: Filter by status=inactive — should find Charlie
echo ""
echo "--- Test 3: Filter by status=inactive ---"
status_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$LIST_URL?status=inactive" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
status_body=$(echo "$status_resp" | head -n -1)
status_status=$(echo "$status_resp" | tail -n 1)
if [ "$status_status" -eq 200 ]; then
    total=$(echo "$status_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2 || true)
    if [ "${total:-0}" -eq 1 ]; then
        echo "PASS: Filter by status (HTTP 200, totalElements=1)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Filter by status — expected totalElements=1, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Filter by status — expected HTTP 200, got HTTP $status_status"
    FAIL=$((FAIL + 1))
fi

# Test 4: Filter by department=Construction — should find Alice + Charlie
echo ""
echo "--- Test 4: Filter by department ---"
dept_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$LIST_URL?department=Construction" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
dept_body=$(echo "$dept_resp" | head -n -1)
dept_status=$(echo "$dept_resp" | tail -n 1)
if [ "$dept_status" -eq 200 ]; then
    total=$(echo "$dept_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2 || true)
    if [ "${total:-0}" -eq 2 ]; then
        echo "PASS: Filter by department (HTTP 200, totalElements=2)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Filter by department — expected totalElements=2, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Filter by department — expected HTTP 200, got HTTP $dept_status"
    FAIL=$((FAIL + 1))
fi

# Test 5: Pagination — page size 2
echo ""
echo "--- Test 5: Pagination (size=2) ---"
page_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$LIST_URL?size=2&page=0" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
page_body=$(echo "$page_resp" | head -n -1)
page_status=$(echo "$page_resp" | tail -n 1)
if [ "$page_status" -eq 200 ]; then
    content_count=$(echo "$page_body" | grep -o '"id":"[^"]*"' | wc -l || true)
    is_last=$(echo "$page_body" | grep -o '"last":[^,}]*' | head -1 | cut -d: -f2 || true)
    if [ "${content_count:-0}" -eq 2 ] && [ "$is_last" = "false" ]; then
        echo "PASS: Pagination (HTTP 200, 2 items, last=false)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Pagination — content_count=$content_count, last=$is_last (expected 2 items, last=false)"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Pagination — expected HTTP 200, got HTTP $page_status"
    FAIL=$((FAIL + 1))
fi

# Test 6: Invalid size (>100) → 400
echo ""
echo "--- Test 6: Invalid page size ---"
run_test "Invalid size" 400 \
    -X GET "$LIST_URL?size=999" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 7: Unauthenticated → 401
echo ""
echo "--- Test 7: Unauthenticated ---"
run_test "Unauthenticated" 401 \
    -X GET "$LIST_URL"

# Test 8: Tenant not found → 404
echo ""
echo "--- Test 8: Tenant not found ---"
run_test "Tenant not found" 404 \
    -X GET "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/employees" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 9: User without employees:list permission → 403
echo ""
echo "--- Test 9: Forbidden (no employees:list permission) ---"
reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"noperm.emplist.${TS}@fams.com\",\"password\":\"Regular@1234\",\"displayName\":\"No Perm\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
reg_body=$(echo "$reg_resp" | head -n -1)
if [ "$reg_status" -eq 201 ]; then
    NO_PERM_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
elif [ "$reg_status" -eq 409 ]; then
    l2=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"noperm.emplist.${TS}@fams.com\",\"password\":\"Regular@1234\"}")
    NO_PERM_TOKEN=$(echo "$l2" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
else
    NO_PERM_TOKEN=""
fi
if [ -n "$NO_PERM_TOKEN" ]; then
    run_test "No permission forbidden" 403 \
        -X GET "$LIST_URL" \
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
