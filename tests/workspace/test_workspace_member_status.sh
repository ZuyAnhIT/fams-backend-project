#!/usr/bin/env bash
# Regression test: employee.status field must be present in workspace member list response.
# Reproduces the bug where status was omitted, causing frontend to show all employees as inactive.
# Usage: BASE_URL=http://localhost:8080 bash test_workspace_member_status.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1" expected="$2"; shift 2
    local actual; actual=$(curl -s -o /dev/null -w "%{http_code}" "$@")
    if [ "$actual" -eq "$expected" ]; then
        echo "PASS: $name (HTTP $actual)"; PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected, got HTTP $actual"; FAIL=$((FAIL + 1))
    fi
}

echo "=== Workspace Member Status Field Regression Test ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ──────────────────────────────────────────────────────────────────────
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
[ "$login_status" -eq 200 ] || { echo "SETUP FAILED: admin login HTTP $login_status"; exit 1; }
TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."

TS=$(date +%s)

echo "--- Setup: Create tenant ---"
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d "{\"name\":\"WS Status Corp\",\"slug\":\"ws-status-$TS\"}")
t_body=$(echo "$t_resp" | head -n -1); t_status=$(echo "$t_resp" | tail -n 1)
[ "$t_status" -eq 201 ] || { echo "SETUP FAILED: create tenant HTTP $t_status"; exit 1; }
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant: $TENANT_ID"

echo "--- Setup: Create employee (active) ---"
emp_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d "{\"firstName\":\"John\",\"lastName\":\"Doe\",\"email\":\"jdoe.$TS@example.com\"}")
emp_body=$(echo "$emp_resp" | head -n -1); emp_status=$(echo "$emp_resp" | tail -n 1)
[ "$emp_status" -eq 201 ] || { echo "SETUP FAILED: create employee HTTP $emp_status"; exit 1; }
EMP_ID=$(echo "$emp_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Employee: $EMP_ID"

echo "--- Setup: Create workspace ---"
ws_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/workspaces" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d "{\"name\":\"Dev Team $TS\"}")
ws_body=$(echo "$ws_resp" | head -n -1); ws_status=$(echo "$ws_resp" | tail -n 1)
[ "$ws_status" -eq 201 ] || { echo "SETUP FAILED: create workspace HTTP $ws_status — $ws_body"; exit 1; }
WS_ID=$(echo "$ws_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Workspace: $WS_ID"

echo "--- Setup: Assign employee to workspace ---"
assign_resp=$(curl -s -w "\n%{http_code}" -X POST \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/workspaces/$WS_ID/members" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"role\":\"member\"}")
assign_status=$(echo "$assign_resp" | tail -n 1)
[ "$assign_status" -eq 201 ] || { echo "SETUP FAILED: assign member HTTP $assign_status"; exit 1; }
echo "Member assigned."
echo ""

# ── Tests ──────────────────────────────────────────────────────────────────────

echo "--- Test 1: GET /members returns employee.status field ---"
list_resp=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/workspaces/$WS_ID/members" \
    -H "Authorization: Bearer $TOKEN")
status_val=$(echo "$list_resp" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
if [ "$status_val" = "active" ]; then
    echo "PASS: employee.status='$status_val' present in response"
    PASS=$((PASS + 1))
else
    echo "FAIL: employee.status missing or wrong — got '$status_val'"
    echo "Body: $list_resp"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 2: assignMember response also includes employee.status ---"
assign_body=$(echo "$assign_resp" | head -n -1)
status_val2=$(echo "$assign_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
if [ "$status_val2" = "active" ]; then
    echo "PASS: assignMember response has employee.status='$status_val2'"
    PASS=$((PASS + 1))
else
    echo "FAIL: assignMember response missing employee.status — got '$status_val2'"
    echo "Body: $assign_body"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 3: GET /members unauthenticated returns 401 ---"
run_test "List members unauthenticated" 401 \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/workspaces/$WS_ID/members"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
[ "$FAIL" -eq 0 ] || exit 1
