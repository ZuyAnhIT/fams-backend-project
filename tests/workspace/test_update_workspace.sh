#!/usr/bin/env bash
# Tests for PUT /api/v1/tenants/{tenantId}/workspaces/{workspaceId}
# Usage: BASE_URL=http://localhost:8080 bash test_update_workspace.sh

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

echo "=== Update Workspace Tests ==="
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

# ── Setup: tenant + workspaces ───────────────────────────────────────────────
echo "--- Setup: Create tenant and workspaces ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Update WS Corp ${TS}\",\"slug\":\"upd-ws-${TS}\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

WS_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/workspaces"

# Create root workspace A
ws_a=$(curl -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Alpha Dept","type":"department"}')
WS_A=$(echo "$ws_a" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create child under A
ws_b=$(curl -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Beta Team\",\"type\":\"team\",\"parentId\":\"$WS_A\"}")
WS_B=$(echo "$ws_b" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create grandchild under B
ws_c=$(curl -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Gamma Squad\",\"type\":\"team\",\"parentId\":\"$WS_B\"}")
WS_C=$(echo "$ws_c" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create another root
ws_d=$(curl -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Delta Dept","type":"department"}')
WS_D=$(echo "$ws_d" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Created: Alpha($WS_A) > Beta($WS_B) > Gamma($WS_C); Delta($WS_D)"
echo ""

# ── Test 1: Update name ───────────────────────────────────────────────────────
echo "--- Test 1: Update name (200) ---"
upd_resp=$(curl -s -w "\n%{http_code}" \
    -X PUT "$WS_URL/$WS_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Alpha Engineering"}')
upd_body=$(echo "$upd_resp" | head -n -1)
upd_status=$(echo "$upd_resp" | tail -n 1)
if [ "$upd_status" -eq 200 ]; then
    updated_name=$(echo "$upd_body" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "PASS: Update name (HTTP 200, new name=$updated_name)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Update name — expected 200, got $upd_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Update description ────────────────────────────────────────────────
echo "--- Test 2: Update description (200) ---"
run_test "Update description" 200 \
    -s -X PUT "$WS_URL/$WS_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"description":"Core engineering division"}'
echo ""

# ── Test 3: Update type ───────────────────────────────────────────────────────
echo "--- Test 3: Update type (200) ---"
run_test "Update type" 200 \
    -s -X PUT "$WS_URL/$WS_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"type":"team"}'
echo ""

# ── Test 4: Update status ─────────────────────────────────────────────────────
echo "--- Test 4: Update status to inactive (200) ---"
run_test "Update status" 200 \
    -s -X PUT "$WS_URL/$WS_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"inactive"}'
echo ""

# ── Test 5: Reparent — move Beta under Delta ──────────────────────────────────
echo "--- Test 5: Reparent workspace (200) ---"
run_test "Reparent to another root" 200 \
    -s -X PUT "$WS_URL/$WS_B" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"parentId\":\"$WS_D\"}"
echo ""

# ── Test 6: Clear parent (make root) ─────────────────────────────────────────
echo "--- Test 6: Clear parent (clearParent=true) (200) ---"
run_test "Clear parent" 200 \
    -s -X PUT "$WS_URL/$WS_B" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"clearParent":true}'
echo ""

# ── Re-parent B under A for circular test ─────────────────────────────────────
curl -s -X PUT "$WS_URL/$WS_B" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"parentId\":\"$WS_A\"}" > /dev/null

# ── Test 7: Self-parent (400) ─────────────────────────────────────────────────
echo "--- Test 7: Self as parent (400) ---"
run_test "Self-parent rejected" 400 \
    -s -X PUT "$WS_URL/$WS_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"parentId\":\"$WS_A\"}"
echo ""

# ── Test 8: Circular reference (400) — set A's parent to C (C is grandchild of A) ──
echo "--- Test 8: Circular parent reference (400) ---"
run_test "Circular reference rejected" 400 \
    -s -X PUT "$WS_URL/$WS_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"parentId\":\"$WS_C\"}"
echo ""

# ── Test 9: Duplicate name (409) ─────────────────────────────────────────────
echo "--- Test 9: Duplicate name (409) ---"
run_test "Duplicate name rejected" 409 \
    -s -X PUT "$WS_URL/$WS_D" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Alpha Engineering"}'
echo ""

# ── Test 10: Case-insensitive duplicate (409) ─────────────────────────────────
echo "--- Test 10: Case-insensitive duplicate (409) ---"
run_test "Case-insensitive duplicate rejected" 409 \
    -s -X PUT "$WS_URL/$WS_D" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"ALPHA ENGINEERING"}'
echo ""

# ── Test 11: Invalid type (400) ───────────────────────────────────────────────
echo "--- Test 11: Invalid type value (400) ---"
run_test "Invalid type rejected" 400 \
    -s -X PUT "$WS_URL/$WS_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"type":"division"}'
echo ""

# ── Test 12: Invalid status (400) ─────────────────────────────────────────────
echo "--- Test 12: Invalid status value (400) ---"
run_test "Invalid status rejected" 400 \
    -s -X PUT "$WS_URL/$WS_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"archived"}'
echo ""

# ── Test 13: Non-existent workspace (404) ─────────────────────────────────────
echo "--- Test 13: Non-existent workspace (404) ---"
run_test "Non-existent workspace" 404 \
    -s -X PUT "$WS_URL/00000000-0000-0000-0000-000000000000" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Ghost"}'
echo ""

# ── Test 14: Non-existent parent (404) ────────────────────────────────────────
echo "--- Test 14: Non-existent parent workspace (404) ---"
run_test "Non-existent parent" 404 \
    -s -X PUT "$WS_URL/$WS_D" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"parentId":"00000000-0000-0000-0000-000000000000"}'
echo ""

# ── Test 15: Unauthenticated (401) ────────────────────────────────────────────
echo "--- Test 15: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X PUT "$WS_URL/$WS_A" \
    -H "Content-Type: application/json" \
    -d '{"name":"Stealth"}'
echo ""

# ── Summary ──────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
