#!/usr/bin/env bash
# Tests for workspace member endpoints:
#   POST   /api/v1/tenants/{tenantId}/workspaces/{workspaceId}/members
#   GET    /api/v1/tenants/{tenantId}/workspaces/{workspaceId}/members
#   DELETE /api/v1/tenants/{tenantId}/workspaces/{workspaceId}/members/{memberId}
# Usage: BASE_URL=http://localhost:8080 bash test_workspace_members.sh

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

echo "=== Workspace Member Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: login ─────────────────────────────────────────────────────────────
echo "--- Setup: Login ---"
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

# ── Setup: tenant ─────────────────────────────────────────────────────────────
echo "--- Setup: Create tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Members Corp ${TS}\",\"slug\":\"members-${TS}\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant: $TENANT_ID"

# ── Setup: workspace ──────────────────────────────────────────────────────────
ws_resp=$(curl -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/workspaces" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Engineering","type":"department"}')
WS_ID=$(echo "$ws_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Workspace: $WS_ID"

# ── Setup: employees ──────────────────────────────────────────────────────────
emp1_resp=$(curl -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Alice\",\"lastName\":\"Smith\",\"employeeCode\":\"EMP-A-${TS}\"}")
EMP1_ID=$(echo "$emp1_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

emp2_resp=$(curl -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Bob\",\"lastName\":\"Jones\",\"employeeCode\":\"EMP-B-${TS}\"}")
EMP2_ID=$(echo "$emp2_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Employees: Alice=$EMP1_ID Bob=$EMP2_ID"
echo ""

MEMBERS_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/workspaces/$WS_ID/members"

# ── Test 1: Assign Alice as member (201) ─────────────────────────────────────
echo "--- Test 1: Assign employee as member (201) ---"
assign_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$MEMBERS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP1_ID\",\"role\":\"member\"}")
assign_body=$(echo "$assign_resp" | head -n -1)
assign_status=$(echo "$assign_resp" | tail -n 1)
if [ "$assign_status" -eq 201 ]; then
    MEMBER1_ID=$(echo "$assign_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "PASS: Assign member (HTTP 201, memberId=$MEMBER1_ID)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Assign member — expected 201, got $assign_status"
    FAIL=$((FAIL + 1))
    MEMBER1_ID=""
fi
echo ""

# ── Test 2: Assign Bob as manager (201) ──────────────────────────────────────
echo "--- Test 2: Assign employee as manager (201) ---"
assign2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$MEMBERS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP2_ID\",\"role\":\"manager\"}")
assign2_body=$(echo "$assign2_resp" | head -n -1)
assign2_status=$(echo "$assign2_resp" | tail -n 1)
if [ "$assign2_status" -eq 201 ]; then
    MEMBER2_ID=$(echo "$assign2_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "PASS: Assign manager (HTTP 201)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Assign manager — expected 201, got $assign2_status"
    FAIL=$((FAIL + 1))
    MEMBER2_ID=""
fi
echo ""

# ── Test 3: Duplicate assignment (409) ───────────────────────────────────────
echo "--- Test 3: Duplicate assignment (409) ---"
run_test "Duplicate assignment rejected" 409 \
    -s -X POST "$MEMBERS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP1_ID\"}"
echo ""

# ── Test 4: Missing employeeId (400) ─────────────────────────────────────────
echo "--- Test 4: Missing employeeId (400) ---"
run_test "Missing employeeId" 400 \
    -s -X POST "$MEMBERS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"role":"member"}'
echo ""

# ── Test 5: Invalid role (400) ────────────────────────────────────────────────
echo "--- Test 5: Invalid role (400) ---"
run_test "Invalid role" 400 \
    -s -X POST "$MEMBERS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP1_ID\",\"role\":\"admin\"}"
echo ""

# ── Test 6: Employee from different tenant (404) ──────────────────────────────
echo "--- Test 6: Non-existent employee (404) ---"
run_test "Non-existent employee" 404 \
    -s -X POST "$MEMBERS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"employeeId":"00000000-0000-0000-0000-000000000000"}'
echo ""

# ── Test 7: Non-existent workspace (404) ──────────────────────────────────────
echo "--- Test 7: Non-existent workspace (404) ---"
run_test "Non-existent workspace" 404 \
    -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/workspaces/00000000-0000-0000-0000-000000000000/members" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP1_ID\"}"
echo ""

# ── Test 8: Unauthenticated (401) ─────────────────────────────────────────────
echo "--- Test 8: No token (401) ---"
run_test "Unauthenticated assign" 401 \
    -s -X POST "$MEMBERS_URL" \
    -H "Content-Type: application/json" \
    -d "{\"employeeId\":\"$EMP1_ID\"}"
echo ""

# ── Test 9: List members (200) ────────────────────────────────────────────────
echo "--- Test 9: List workspace members (200) ---"
list_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$MEMBERS_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_body=$(echo "$list_resp" | head -n -1)
list_status=$(echo "$list_resp" | tail -n 1)
if [ "$list_status" -eq 200 ]; then
    total=$(echo "$list_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    echo "PASS: List members (HTTP 200, totalElements=$total)"
    PASS=$((PASS + 1))
else
    echo "FAIL: List members — expected 200, got $list_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 10: List unauthenticated (401) ───────────────────────────────────────
echo "--- Test 10: List without token (401) ---"
run_test "Unauthenticated list" 401 \
    -s -X GET "$MEMBERS_URL"
echo ""

# ── Test 11: Remove member (204) ──────────────────────────────────────────────
if [ -n "${MEMBER1_ID:-}" ]; then
    echo "--- Test 11: Remove member (204) ---"
    run_test "Remove member" 204 \
        -s -X DELETE "$MEMBERS_URL/$MEMBER1_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN"
    echo ""

    # ── Test 12: Re-assign after removal (201) ────────────────────────────────
    echo "--- Test 12: Re-assign after removal (201) ---"
    run_test "Re-assign after removal" 201 \
        -s -X POST "$MEMBERS_URL" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"employeeId\":\"$EMP1_ID\",\"role\":\"lead\"}"
    echo ""
fi

# ── Test 13: Remove non-existent membership (404) ────────────────────────────
echo "--- Test 13: Remove non-existent membership (404) ---"
run_test "Remove non-existent member" 404 \
    -s -X DELETE "$MEMBERS_URL/00000000-0000-0000-0000-000000000000" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 14: Remove unauthenticated (401) ─────────────────────────────────────
if [ -n "${MEMBER2_ID:-}" ]; then
    echo "--- Test 14: Remove without token (401) ---"
    run_test "Unauthenticated remove" 401 \
        -s -X DELETE "$MEMBERS_URL/$MEMBER2_ID"
    echo ""
fi

# ── Summary ──────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
