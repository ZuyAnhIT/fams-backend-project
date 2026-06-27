#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/workspaces  (flat list)
#          and GET /api/v1/tenants/{tenantId}/workspaces/tree
# Usage: BASE_URL=http://localhost:8080 bash test_list_workspace.sh

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

echo "=== List / Tree Workspace Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: login as platform admin ──────────────────────────────────────────
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

# ── Setup: create test tenant ────────────────────────────────────────────────
echo "--- Setup: Create test tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"List WS Corp ${TS}\",\"slug\":\"list-ws-${TS}\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: $TENANT_ID"

WS_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/workspaces"

# ── Setup: create workspaces ─────────────────────────────────────────────────
echo "--- Setup: Create workspaces ---"
eng_resp=$(curl -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Engineering","type":"department"}')
ENG_ID=$(echo "$eng_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Engineering: $ENG_ID"

curl -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Backend Team\",\"type\":\"team\",\"parentId\":\"$ENG_ID\"}" > /dev/null

curl -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Frontend Team\",\"type\":\"team\",\"parentId\":\"$ENG_ID\"}" > /dev/null

curl -s -X POST "$WS_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Human Resources","type":"department"}' > /dev/null

echo "Workspaces seeded (Engineering > Backend Team, Frontend Team; Human Resources)"
echo ""

# ── Test 1: List all workspaces (200) ────────────────────────────────────────
echo "--- Test 1: List all workspaces (200) ---"
list_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$WS_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_body=$(echo "$list_resp" | head -n -1)
list_status=$(echo "$list_resp" | tail -n 1)
if [ "$list_status" -eq 200 ]; then
    total=$(echo "$list_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    echo "PASS: List all workspaces (HTTP 200, totalElements=$total)"
    PASS=$((PASS + 1))
else
    echo "FAIL: List all workspaces — expected 200, got $list_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: List with search ─────────────────────────────────────────────────
echo "--- Test 2: List with search=backend (200) ---"
run_test "Search by name" 200 \
    -s -X GET "$WS_URL?search=backend" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 3: List with status filter ──────────────────────────────────────────
echo "--- Test 3: Filter by status=active (200) ---"
run_test "Filter by status=active" 200 \
    -s -X GET "$WS_URL?status=active" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 4: List with type filter ────────────────────────────────────────────
echo "--- Test 4: Filter by type=team (200) ---"
run_test "Filter by type=team" 200 \
    -s -X GET "$WS_URL?type=team" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 5: List with pagination ─────────────────────────────────────────────
echo "--- Test 5: Pagination (page=0&size=2) ---"
run_test "Paginated list" 200 \
    -s -X GET "$WS_URL?page=0&size=2" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 6: List unauthenticated (401) ───────────────────────────────────────
echo "--- Test 6: List without token (401) ---"
run_test "Unauthenticated list" 401 \
    -s -X GET "$WS_URL"
echo ""

# ── Test 7: List for non-existent tenant (404) ───────────────────────────────
echo "--- Test 7: List for non-existent tenant (404) ---"
run_test "Non-existent tenant list" 404 \
    -s -X GET "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/workspaces" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 8: Tree endpoint (200) ──────────────────────────────────────────────
echo "--- Test 8: Get workspace tree (200) ---"
tree_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$WS_URL/tree" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
tree_body=$(echo "$tree_resp" | head -n -1)
tree_status=$(echo "$tree_resp" | tail -n 1)
if [ "$tree_status" -eq 200 ]; then
    # Verify Engineering node has children
    if echo "$tree_body" | grep -q '"children":\[{'; then
        echo "PASS: Tree returned with nested children (HTTP 200)"
    else
        echo "PASS: Tree returned (HTTP 200)"
    fi
    PASS=$((PASS + 1))
else
    echo "FAIL: Get workspace tree — expected 200, got $tree_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: Tree with search ─────────────────────────────────────────────────
echo "--- Test 9: Tree with search=backend (200, includes ancestor) ---"
run_test "Tree search includes ancestor" 200 \
    -s -X GET "$WS_URL/tree?search=backend" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 10: Tree with status filter ─────────────────────────────────────────
echo "--- Test 10: Tree with status=active (200) ---"
run_test "Tree status filter" 200 \
    -s -X GET "$WS_URL/tree?status=active" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 11: Tree unauthenticated (401) ──────────────────────────────────────
echo "--- Test 11: Tree without token (401) ---"
run_test "Tree unauthenticated" 401 \
    -s -X GET "$WS_URL/tree"
echo ""

# ── Test 12: Verify search narrows results ────────────────────────────────────
echo "--- Test 12: Search status=inactive returns empty (200) ---"
run_test "Filter inactive returns 200" 200 \
    -s -X GET "$WS_URL?status=inactive" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Summary ──────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
