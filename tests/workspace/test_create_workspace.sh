#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/workspaces (create workspace)
# Usage: BASE_URL=http://localhost:8080 bash test_create_workspace.sh

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

echo "=== Create Workspace Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: login as platform admin ──────────────────────────────────────────
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

# ── Setup: create test tenant ────────────────────────────────────────────────
echo "--- Setup: Create test tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"WS Test Corp ${TS}\",\"slug\":\"ws-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
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

# ── Test 1: Happy path — department, no parent ───────────────────────────────
echo "--- Test 1: Happy path (department, minimal fields) ---"
create_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{
      \"name\": \"Engineering\",
      \"description\": \"Core engineering department\",
      \"type\": \"department\"
    }")
create_body=$(echo "$create_resp" | head -n -1)
create_status=$(echo "$create_resp" | tail -n 1)
if [ "$create_status" -eq 201 ]; then
    echo "PASS: Test 1 — Happy path (HTTP 201)"
    PASS=$((PASS + 1))
    WS_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "  Workspace id=$WS_ID"
else
    echo "FAIL: Test 1 — expected 201, got $create_status"
    FAIL=$((FAIL + 1))
    WS_ID=""
fi
echo ""

# ── Test 2: Happy path — team with parent ────────────────────────────────────
if [ -n "${WS_ID:-}" ]; then
    echo "--- Test 2: Happy path (team with parent) ---"
    run_test "Create team with parent" 201 \
        -s -X POST "$WS_URL" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"name\":\"Backend Team\",\"type\":\"team\",\"parentId\":\"$WS_ID\"}"
    echo ""
fi

# ── Test 3: Duplicate name ───────────────────────────────────────────────────
echo "--- Test 3: Duplicate name (409) ---"
run_test "Duplicate workspace name" 409 \
    -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Engineering"}'
echo ""

# ── Test 4: Case-insensitive duplicate ──────────────────────────────────────
echo "--- Test 4: Case-insensitive duplicate (409) ---"
run_test "Case-insensitive duplicate" 409 \
    -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"ENGINEERING"}'
echo ""

# ── Test 5: Missing name (400) ───────────────────────────────────────────────
echo "--- Test 5: Missing name (400) ---"
run_test "Missing name" 400 \
    -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"description":"No name"}'
echo ""

# ── Test 6: Invalid type (400) ───────────────────────────────────────────────
echo "--- Test 6: Invalid type (400) ---"
run_test "Invalid type" 400 \
    -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HR Group","type":"division"}'
echo ""

# ── Test 7: Name too long (400) ──────────────────────────────────────────────
echo "--- Test 7: Name too long (400) ---"
LONG_NAME=$(python3 -c "print('A'*101)")
run_test "Name too long" 400 \
    -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"$LONG_NAME\"}"
echo ""

# ── Test 8: Unauthenticated (401) ────────────────────────────────────────────
echo "--- Test 8: No token (401) ---"
run_test "No auth token" 401 \
    -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -d '{"name":"Stealth Dept"}'
echo ""

# ── Test 9: Non-existent parent workspace (404) ──────────────────────────────
echo "--- Test 9: Non-existent parent (404) ---"
run_test "Non-existent parent workspace" 404 \
    -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Orphan Team\",\"parentId\":\"00000000-0000-0000-0000-000000000000\"}"
echo ""

# ── Test 10: GET workspace (200) ─────────────────────────────────────────────
if [ -n "${WS_ID:-}" ]; then
    echo "--- Test 10: Get workspace (200) ---"
    run_test "Get created workspace" 200 \
        -s -X GET "$WS_URL/$WS_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN"
    echo ""
fi

# ── Test 11: GET non-existent workspace (404) ────────────────────────────────
echo "--- Test 11: Get non-existent workspace (404) ---"
run_test "Get non-existent workspace" 404 \
    -s -X GET "$WS_URL/00000000-0000-0000-0000-000000000000" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Summary ──────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
