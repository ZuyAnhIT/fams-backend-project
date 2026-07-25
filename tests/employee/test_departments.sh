#!/usr/bin/env bash
# Tests for Department CRUD and Employee Code Auto-Generation
# Usage: BASE_URL=http://localhost:8080 bash test_departments.sh

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

echo "=== Department CRUD + Employee Code Auto-Gen Tests ==="
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

# Setup: create tenant A
echo "--- Setup: Create test tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Dept Test Corp\",\"slug\":\"dept-test-${TS}\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: id=$TENANT_ID"

# Setup: create a second tenant for cross-tenant test
t2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Other Corp\",\"slug\":\"other-${TS}\"}")
t2_body=$(echo "$t2_resp" | head -n -1)
t2_status=$(echo "$t2_resp" | tail -n 1)
if [ "$t2_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create second tenant (HTTP $t2_status)"
    exit 1
fi
TENANT2_ID=$(echo "$t2_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant 2 created: id=$TENANT2_ID"
echo ""

DEPT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/departments"
EMP_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees"

# ─── Test 1: Create department (happy path) → 201 ───
echo "--- Test 1: Create department (happy path) ---"
create_dept_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$DEPT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Engineering","description":"Software development team"}')
create_dept_body=$(echo "$create_dept_resp" | head -n -1)
create_dept_status=$(echo "$create_dept_resp" | tail -n 1)
if [ "$create_dept_status" -eq 201 ]; then
    DEPT_ID=$(echo "$create_dept_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    dept_name=$(echo "$create_dept_body" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ -n "$DEPT_ID" ] && [ "$dept_name" = "Engineering" ]; then
        echo "PASS: Create department (HTTP 201, id=$DEPT_ID, name=$dept_name)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Create department — unexpected body: $create_dept_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Create department — expected HTTP 201, got HTTP $create_dept_status"
    echo "Body: $create_dept_body"
    FAIL=$((FAIL + 1))
    DEPT_ID=""
fi

# ─── Test 2: Create duplicate department (case-insensitive) → 409 ───
echo ""
echo "--- Test 2: Create duplicate department (case-insensitive) ---"
run_test "Duplicate department name" 409 \
    -X POST "$DEPT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"engineering"}'

# ─── Test 3: Create department with blank name → 400 ───
echo ""
echo "--- Test 3: Create department with blank name ---"
run_test "Blank department name" 400 \
    -X POST "$DEPT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":""}'

# ─── Test 4: Create department unauthenticated → 401 ───
echo ""
echo "--- Test 4: Create department unauthenticated ---"
run_test "Unauthenticated create department" 401 \
    -X POST "$DEPT_URL" \
    -H "Content-Type: application/json" \
    -d '{"name":"Marketing"}'

# ─── Test 5: List departments → 200, contains created department ───
echo ""
echo "--- Test 5: List departments ---"
list_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$DEPT_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_body=$(echo "$list_resp" | head -n -1)
list_status=$(echo "$list_resp" | tail -n 1)
if [ "$list_status" -eq 200 ]; then
    if echo "$list_body" | grep -q "Engineering"; then
        echo "PASS: List departments (HTTP 200, contains 'Engineering')"
        PASS=$((PASS + 1))
    else
        echo "FAIL: List departments — 'Engineering' not found in response"
        echo "Body: $list_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: List departments — expected HTTP 200, got HTTP $list_status"
    echo "Body: $list_body"
    FAIL=$((FAIL + 1))
fi

# ─── Test 6: Update department name → 200 ───
echo ""
echo "--- Test 6: Update department name ---"
if [ -n "${DEPT_ID:-}" ]; then
    update_resp=$(curl -s -w "\n%{http_code}" \
        -X PATCH "$DEPT_URL/$DEPT_ID" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d '{"name":"Engineering & Platform","description":"Updated description"}')
    update_body=$(echo "$update_resp" | head -n -1)
    update_status=$(echo "$update_resp" | tail -n 1)
    if [ "$update_status" -eq 200 ]; then
        updated_name=$(echo "$update_body" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)
        if [ "$updated_name" = "Engineering & Platform" ]; then
            echo "PASS: Update department (HTTP 200, name='$updated_name')"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Update department — name='$updated_name', body: $update_body"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Update department — expected HTTP 200, got HTTP $update_status"
        echo "Body: $update_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Update department — no department ID available"
fi

# ─── Test 7: Delete department → 200 ───
echo ""
echo "--- Test 7: Delete department ---"
# Create a separate department to delete so we keep the main one
del_dept_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$DEPT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"ToDelete"}')
del_dept_body=$(echo "$del_dept_resp" | head -n -1)
del_dept_status=$(echo "$del_dept_resp" | tail -n 1)
if [ "$del_dept_status" -eq 201 ]; then
    DEL_DEPT_ID=$(echo "$del_dept_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    run_test "Delete department" 200 \
        -X DELETE "$DEPT_URL/$DEL_DEPT_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN"
else
    echo "SKIP: Delete department — could not create department to delete (HTTP $del_dept_status)"
fi

# ─── Test 8: List after delete → 200, does not contain deleted dept ───
echo ""
echo "--- Test 8: List after delete (deleted dept absent) ---"
list2_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$DEPT_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list2_body=$(echo "$list2_resp" | head -n -1)
list2_status=$(echo "$list2_resp" | tail -n 1)
if [ "$list2_status" -eq 200 ]; then
    if echo "$list2_body" | grep -q "ToDelete"; then
        echo "FAIL: List after delete — 'ToDelete' still present in response"
        FAIL=$((FAIL + 1))
    else
        echo "PASS: List after delete (HTTP 200, 'ToDelete' absent)"
        PASS=$((PASS + 1))
    fi
else
    echo "FAIL: List after delete — expected HTTP 200, got HTTP $list2_status"
    FAIL=$((FAIL + 1))
fi

# ─── Test 9: Create employee with departmentId → 201, departmentId in response ───
echo ""
echo "--- Test 9: Create employee with departmentId ---"
if [ -n "${DEPT_ID:-}" ]; then
    emp_dept_resp=$(curl -s -w "\n%{http_code}" \
        -X POST "$EMP_URL" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{
          \"firstName\": \"Alice\",
          \"lastName\": \"Engineer\",
          \"departmentId\": \"$DEPT_ID\"
        }")
    emp_dept_body=$(echo "$emp_dept_resp" | head -n -1)
    emp_dept_status=$(echo "$emp_dept_resp" | tail -n 1)
    if [ "$emp_dept_status" -eq 201 ]; then
        emp_dept_id=$(echo "$emp_dept_body" | grep -o '"departmentId":"[^"]*"' | head -1 | cut -d'"' -f4)
        if [ "$emp_dept_id" = "$DEPT_ID" ]; then
            echo "PASS: Create employee with departmentId (HTTP 201, departmentId=$emp_dept_id)"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Create employee with departmentId — departmentId='$emp_dept_id', body: $emp_dept_body"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Create employee with departmentId — expected HTTP 201, got HTTP $emp_dept_status"
        echo "Body: $emp_dept_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Create employee with departmentId — no department ID available"
fi

# ─── Test 10-12: Employee code auto-generation ───
echo ""
echo "--- Setup: Configure employee code prefix ---"
settings_resp=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/settings" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"employeeCodePrefix":"EMP","employeeCodePadding":4}')
settings_body=$(echo "$settings_resp" | head -n -1)
settings_status=$(echo "$settings_resp" | tail -n 1)

# ─── Test 12: Update tenant settings employee code format → 200 ───
echo "--- Test 12: Update tenant settings employee code format ---"
if [ "$settings_status" -eq 200 ]; then
    prefix=$(echo "$settings_body" | grep -o '"employeeCodePrefix":"[^"]*"' | head -1 | cut -d'"' -f4)
    padding=$(echo "$settings_body" | grep -o '"employeeCodePadding":[0-9]*' | head -1 | cut -d':' -f2)
    if [ "$prefix" = "EMP" ] && [ "$padding" = "4" ]; then
        echo "PASS: Update tenant settings employee code format (HTTP 200, prefix=$prefix, padding=$padding)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Update settings — prefix='$prefix', padding='$padding', body: $settings_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Update tenant settings — expected HTTP 200, got HTTP $settings_status"
    echo "Body: $settings_body"
    FAIL=$((FAIL + 1))
fi

# ─── Test 10: Create employee without code (with prefix configured) → 201, auto-generated ───
echo ""
echo "--- Test 10: Create employee without code (auto-generated) ---"
auto_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Auto","lastName":"Coder"}')
auto_body=$(echo "$auto_resp" | head -n -1)
auto_status=$(echo "$auto_resp" | tail -n 1)
if [ "$auto_status" -eq 201 ]; then
    auto_code=$(echo "$auto_body" | grep -o '"employeeCode":"[^"]*"' | head -1 | cut -d'"' -f4)
    if echo "$auto_code" | grep -qE "^EMP-[0-9]{4}$"; then
        echo "PASS: Create employee without code (HTTP 201, employeeCode=$auto_code)"
        PASS=$((PASS + 1))
        FIRST_CODE="$auto_code"
    else
        echo "FAIL: Create employee without code — employeeCode='$auto_code' does not match EMP-NNNN pattern"
        echo "Body: $auto_body"
        FAIL=$((FAIL + 1))
        FIRST_CODE=""
    fi
else
    echo "FAIL: Create employee without code — expected HTTP 201, got HTTP $auto_status"
    echo "Body: $auto_body"
    FAIL=$((FAIL + 1))
    FIRST_CODE=""
fi

# ─── Test 11: Create two employees without codes → sequential codes ───
echo ""
echo "--- Test 11: Create two employees without codes (sequential) ---"
seq_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Seq","lastName":"Two"}')
seq_body=$(echo "$seq_resp" | head -n -1)
seq_status=$(echo "$seq_resp" | tail -n 1)
if [ "$seq_status" -eq 201 ]; then
    seq_code=$(echo "$seq_body" | grep -o '"employeeCode":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ -n "${FIRST_CODE:-}" ] && [ -n "$seq_code" ] && [ "$seq_code" != "$FIRST_CODE" ]; then
        echo "PASS: Sequential codes (first=$FIRST_CODE, second=$seq_code)"
        PASS=$((PASS + 1))
    elif [ -z "${FIRST_CODE:-}" ]; then
        echo "SKIP: Sequential check — first code not captured"
    else
        echo "FAIL: Sequential codes — first='${FIRST_CODE:-}', second='$seq_code' (should differ)"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Create second employee without code — expected HTTP 201, got HTTP $seq_status"
    echo "Body: $seq_body"
    FAIL=$((FAIL + 1))
fi

# ─── Test 13: Create department in wrong tenant → 404 ───
echo ""
echo "--- Test 13: Create department in wrong tenant (non-existent) ---"
run_test "Create department in non-existent tenant" 404 \
    -X POST "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/departments" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Ghost"}'

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
