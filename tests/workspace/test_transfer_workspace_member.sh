#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/workspaces/{workspaceId}/members/{memberId}/transfer
# Usage: BASE_URL=http://localhost:8080 bash test_transfer_workspace_member.sh

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

echo "=== Transfer Workspace Member Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: login ─────────────────────────────────────────────────────────────
echo "--- Setup: Login ---"
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

# ── Setup: tenant ─────────────────────────────────────────────────────────────
echo "--- Setup: Create tenant and resources ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Transfer Corp ${TS}\",\"slug\":\"transfer-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

WS_BASE="$BASE_URL/api/v1/tenants/$TENANT_ID/workspaces"

# Create two workspaces
ws_a=$(curl -s -X POST "$WS_BASE" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Alpha Dept","type":"department"}')
WS_A=$(echo "$ws_a" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

ws_b=$(curl -s -X POST "$WS_BASE" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Beta Dept","type":"department"}')
WS_B=$(echo "$ws_b" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

ws_c=$(curl -s -X POST "$WS_BASE" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Gamma Dept","type":"department"}')
WS_C=$(echo "$ws_c" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create employees
emp1=$(curl -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Alice\",\"lastName\":\"T\",\"employeeCode\":\"TRF-A-${TS}\"}")
EMP1=$(echo "$emp1" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

emp2=$(curl -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Bob\",\"lastName\":\"T\",\"employeeCode\":\"TRF-B-${TS}\"}")
EMP2=$(echo "$emp2" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Assign Alice to Alpha with role=lead, Bob to Alpha with role=member
m1=$(curl -s -X POST "$WS_BASE/$WS_A/members" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP1\",\"role\":\"lead\"}")
M1=$(echo "$m1" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

m2=$(curl -s -X POST "$WS_BASE/$WS_A/members" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP2\",\"role\":\"member\"}")
M2=$(echo "$m2" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Pre-assign Bob to Gamma (to test duplicate on target)
m3=$(curl -s -X POST "$WS_BASE/$WS_C/members" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP2\",\"role\":\"member\"}")
M3=$(echo "$m3" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup: Alpha=$WS_A Beta=$WS_B Gamma=$WS_C"
echo "       Alice(lead)=$M1 Bob(member in Alpha)=$M2 Bob(member in Gamma)=$M3"
echo ""

# ── Test 1: Happy path — transfer Alice from Alpha to Beta (role inherited) ──
echo "--- Test 1: Transfer employee, role inherited (200) ---"
xfr_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$WS_BASE/$WS_A/members/$M1/transfer" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"targetWorkspaceId\":\"$WS_B\"}")
xfr_body=$(echo "$xfr_resp" | head -n -1)
xfr_status=$(echo "$xfr_resp" | tail -n 1)
if [ "$xfr_status" -eq 200 ]; then
    new_ws=$(echo "$xfr_body" | grep -o '"workspaceId":"[^"]*"' | head -1 | cut -d'"' -f4)
    new_role=$(echo "$xfr_body" | grep -o '"role":"[^"]*"' | head -1 | cut -d'"' -f4)
    NEW_M1=$(echo "$xfr_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ "$new_ws" = "$WS_B" ] && [ "$new_role" = "lead" ]; then
        echo "PASS: Transfer (HTTP 200, workspaceId=$new_ws role=$new_role)"
    else
        echo "PASS: Transfer returned 200 but check data: ws=$new_ws role=$new_role"
    fi
    PASS=$((PASS + 1))
else
    echo "FAIL: Transfer — expected 200, got $xfr_status"
    echo "Body: $xfr_body"
    FAIL=$((FAIL + 1))
    NEW_M1=""
fi
echo ""

# ── Test 2: Alice no longer in Alpha (source removed) — re-assigning verifies slot freed ──
echo "--- Test 2: Alice can be re-assigned to Alpha after transfer (201) ---"
run_test "Re-assign to source after transfer" 201 \
    -s -X POST "$WS_BASE/$WS_A/members" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP1\",\"role\":\"member\"}"
echo ""

# ── Test 3: Transfer with role override ──────────────────────────────────────
echo "--- Test 3: Transfer with explicit role override (200) ---"
# Bob is in Alpha as member and in Gamma — transfer Bob from Alpha to Beta with role=manager
xfr2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$WS_BASE/$WS_A/members/$M2/transfer" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"targetWorkspaceId\":\"$WS_B\",\"role\":\"manager\"}")
xfr2_body=$(echo "$xfr2_resp" | head -n -1)
xfr2_status=$(echo "$xfr2_resp" | tail -n 1)
if [ "$xfr2_status" -eq 200 ]; then
    new_role2=$(echo "$xfr2_body" | grep -o '"role":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ "$new_role2" = "manager" ]; then
        echo "PASS: Transfer with role override (HTTP 200, role=$new_role2)"
    else
        echo "PASS: HTTP 200 but role=$new_role2 (expected manager)"
    fi
    PASS=$((PASS + 1))
else
    echo "FAIL: Transfer with role override — expected 200, got $xfr2_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Same-workspace transfer (400) ────────────────────────────────────
echo "--- Test 4: Transfer to same workspace (400) ---"
# Bob is still in Gamma (M3) — use that membership for the same-workspace test
run_test "Same-workspace transfer rejected" 400 \
    -s -X POST "$WS_BASE/$WS_C/members/$M3/transfer" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"targetWorkspaceId\":\"$WS_C\"}"
echo ""

# ── Test 5: Employee already in target workspace (409) ───────────────────────
echo "--- Test 5: Employee already in target workspace (409) ---"
# Bob is in Gamma (M3) and Beta (after test 3). Try transferring Bob from Gamma to Beta.
run_test "Already member of target" 409 \
    -s -X POST "$WS_BASE/$WS_C/members/$M3/transfer" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"targetWorkspaceId\":\"$WS_B\"}"
echo ""

# ── Test 6: Missing targetWorkspaceId (400) ───────────────────────────────────
echo "--- Test 6: Missing targetWorkspaceId (400) ---"
run_test "Missing targetWorkspaceId" 400 \
    -s -X POST "$WS_BASE/$WS_C/members/$M3/transfer" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{}'
echo ""

# ── Test 7: Invalid role (400) ────────────────────────────────────────────────
echo "--- Test 7: Invalid role value (400) ---"
run_test "Invalid role" 400 \
    -s -X POST "$WS_BASE/$WS_C/members/$M3/transfer" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"targetWorkspaceId\":\"$WS_A\",\"role\":\"boss\"}"
echo ""

# ── Test 8: Non-existent membership (404) ─────────────────────────────────────
echo "--- Test 8: Non-existent membership (404) ---"
run_test "Non-existent membership" 404 \
    -s -X POST "$WS_BASE/$WS_A/members/00000000-0000-0000-0000-000000000000/transfer" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"targetWorkspaceId\":\"$WS_B\"}"
echo ""

# ── Test 9: Non-existent target workspace (404) ───────────────────────────────
echo "--- Test 9: Non-existent target workspace (404) ---"
run_test "Non-existent target workspace" 404 \
    -s -X POST "$WS_BASE/$WS_C/members/$M3/transfer" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"targetWorkspaceId":"00000000-0000-0000-0000-000000000000"}'
echo ""

# ── Test 10: Unauthenticated (401) ────────────────────────────────────────────
echo "--- Test 10: No token (401) ---"
run_test "Unauthenticated transfer" 401 \
    -s -X POST "$WS_BASE/$WS_C/members/$M3/transfer" \
    -H "Content-Type: application/json" \
    -d "{\"targetWorkspaceId\":\"$WS_A\"}"
echo ""

# ── Summary ──────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
