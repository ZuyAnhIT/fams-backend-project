#!/usr/bin/env bash
# Tests for Department-via-Workspace linkage (employees.departmentId -> workspaces of type=department)
# and Employee Code Auto-Generation.
#
# Department CRUD was consolidated into the Workspace module (see
# docs/api/workspace-management-api.md section 4) — the standalone /departments endpoint no
# longer exists. "Department" management now means: POST/PUT/DELETE on
# /tenants/{tenantId}/workspaces with type=department (already covered end-to-end by
# tests/workspace/*.sh). This file only re-verifies the Employee <-> Workspace linkage
# (employees.departmentId referencing a workspace) plus employee-code auto-generation.
#
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

echo "=== Employee <-> Workspace(department) Linkage + Employee Code Auto-Gen Tests ==="
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
    -d "{\"name\":\"Dept Test Corp\",\"slug\":\"dept-test-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: id=$TENANT_ID"
echo ""

WS_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/workspaces"
EMP_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/employees"

# ─── Test 1: Create a workspace of type=department (this is now how "departments" are made) ───
echo "--- Test 1: Create workspace type=department (happy path) ---"
create_ws_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Engineering","description":"Software development team","type":"department"}')
create_ws_body=$(echo "$create_ws_resp" | head -n -1)
create_ws_status=$(echo "$create_ws_resp" | tail -n 1)
if [ "$create_ws_status" -eq 201 ]; then
    DEPT_ID=$(echo "$create_ws_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    ws_name=$(echo "$create_ws_body" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ -n "$DEPT_ID" ] && [ "$ws_name" = "Engineering" ]; then
        echo "PASS: Create workspace type=department (HTTP 201, id=$DEPT_ID, name=$ws_name)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Create workspace — unexpected body: $create_ws_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Create workspace — expected HTTP 201, got HTTP $create_ws_status"
    echo "Body: $create_ws_body"
    FAIL=$((FAIL + 1))
    DEPT_ID=""
fi

# ─── Test 2: Create employee with departmentId (now a Workspace id) → 201, department synced ───
echo ""
echo "--- Test 2: Create employee with departmentId (Workspace id) ---"
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
        emp_dept_name=$(echo "$emp_dept_body" | grep -o '"department":"[^"]*"' | head -1 | cut -d'"' -f4)
        if [ "$emp_dept_id" = "$DEPT_ID" ] && [ "$emp_dept_name" = "Engineering" ]; then
            echo "PASS: Create employee with departmentId (HTTP 201, departmentId=$emp_dept_id, department='$emp_dept_name')"
            PASS=$((PASS + 1))
            EMP_ID="$(echo "$emp_dept_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"
        else
            echo "FAIL: Create employee with departmentId — departmentId='$emp_dept_id' department='$emp_dept_name', body: $emp_dept_body"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Create employee with departmentId — expected HTTP 201, got HTTP $emp_dept_status"
        echo "Body: $emp_dept_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Create employee with departmentId — no workspace ID available"
fi

# ─── Test 3: Create employee with non-existent departmentId → 404 ───
echo ""
echo "--- Test 3: Create employee with non-existent departmentId ---"
run_test "Non-existent departmentId" 404 \
    -X POST "$EMP_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"firstName":"Ghost","lastName":"Worker","departmentId":"00000000-0000-0000-0000-000000000000"}'

# ─── Test 4: Update employee, re-point departmentId to a second workspace ───
echo ""
echo "--- Test 4: Update employee departmentId to a different workspace ---"
create_ws2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Platform","type":"department"}')
create_ws2_body=$(echo "$create_ws2_resp" | head -n -1)
DEPT2_ID=$(echo "$create_ws2_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -n "${EMP_ID:-}" ] && [ -n "${DEPT2_ID:-}" ]; then
    upd_resp=$(curl -s -w "\n%{http_code}" \
        -X PATCH "$EMP_URL/$EMP_ID" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"departmentId\":\"$DEPT2_ID\"}")
    upd_body=$(echo "$upd_resp" | head -n -1)
    upd_status=$(echo "$upd_resp" | tail -n 1)
    if [ "$upd_status" -eq 200 ]; then
        upd_dept_name=$(echo "$upd_body" | grep -o '"department":"[^"]*"' | head -1 | cut -d'"' -f4)
        if [ "$upd_dept_name" = "Platform" ]; then
            echo "PASS: Update employee departmentId (HTTP 200, department='$upd_dept_name')"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Update employee departmentId — department='$upd_dept_name', body: $upd_body"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Update employee departmentId — expected HTTP 200, got HTTP $upd_status"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Update employee departmentId — missing employee or second workspace ID"
fi

# ─── Test 5: Filter employee list by department (string) still works post-consolidation ───
echo ""
echo "--- Test 5: List employees filtered by department=Platform ---"
list_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$EMP_URL?department=Platform" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_body=$(echo "$list_resp" | head -n -1)
list_status=$(echo "$list_resp" | tail -n 1)
if [ "$list_status" -eq 200 ] && echo "$list_body" | grep -q "Platform"; then
    echo "PASS: List employees filtered by department (HTTP 200, contains 'Platform')"
    PASS=$((PASS + 1))
else
    echo "FAIL: List employees filtered by department — status=$list_status body=$list_body"
    FAIL=$((FAIL + 1))
fi

# ─── Test 6-8: Employee code auto-generation (unrelated to department consolidation) ───
echo ""
echo "--- Setup: Configure employee code prefix ---"
settings_resp=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/settings" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"employeeCodePrefix":"EMP","employeeCodePadding":4}')
settings_body=$(echo "$settings_resp" | head -n -1)
settings_status=$(echo "$settings_resp" | tail -n 1)

echo "--- Test 6: Update tenant settings employee code format ---"
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

echo ""
echo "--- Test 7: Create employee without code (auto-generated) ---"
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

echo ""
echo "--- Test 8: Create two employees without codes (sequential) ---"
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

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
