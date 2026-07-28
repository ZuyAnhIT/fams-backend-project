#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/sites (create site)
# Usage: BASE_URL=http://localhost:8080 bash test_create_site.sh

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

echo "=== Create Site Tests ==="
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
ENTERPRISE_PLAN_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "SELECT id FROM plans WHERE name='enterprise';" | tr -d " \n")
echo "Admin token obtained."
echo ""

# ── Setup: create tenant ──────────────────────────────────────────────────────
echo "--- Setup: Create tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Site Corp ${TS}\",\"slug\":\"site-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
t_body=$(echo "$t_resp" | head -n -1)
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant (HTTP $t_status)"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" -d "{\"planId\":\"$ENTERPRISE_PLAN_ID\"}"  # bump trial->enterprise so site-limit (1) does not block multi-site tests
echo "Tenant: $TENANT_ID"
echo ""

SITE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites"

# ── Test 1: Happy path — full data ────────────────────────────────────────────
echo "--- Test 1: Happy path (all fields) ---"
create_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$SITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{
      \"name\": \"Hanoi Tower Project\",
      \"code\": \"HN-001\",
      \"description\": \"Main site in Ba Dinh district\",
      \"address\": \"123 Ba Dinh, Hanoi\",
      \"latitude\": 21.0285,
      \"longitude\": 105.8542,
      \"timezone\": \"Asia/Ho_Chi_Minh\"
    }")
create_body=$(echo "$create_resp" | head -n -1)
create_status=$(echo "$create_resp" | tail -n 1)
if [ "$create_status" -eq 201 ]; then
    SITE_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "PASS: Create site (HTTP 201, id=$SITE_ID)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Create site — expected 201, got $create_status"
    echo "Body: $create_body"
    FAIL=$((FAIL + 1))
    SITE_ID=""
fi
echo ""

# ── Test 2: Happy path — minimal (name only) ─────────────────────────────────
echo "--- Test 2: Happy path (name only) ---"
run_test "Create site minimal" 201 \
    -s -X POST "$SITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HCMC Site"}'
echo ""

# ── Test 3: Duplicate name (409) ─────────────────────────────────────────────
echo "--- Test 3: Duplicate name (409) ---"
run_test "Duplicate site name" 409 \
    -s -X POST "$SITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Hanoi Tower Project"}'
echo ""

# ── Test 4: Case-insensitive duplicate name (409) ────────────────────────────
echo "--- Test 4: Case-insensitive duplicate name (409) ---"
run_test "Case-insensitive duplicate name" 409 \
    -s -X POST "$SITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HANOI TOWER PROJECT"}'
echo ""

# ── Test 5: Duplicate code (409) ──────────────────────────────────────────────
echo "--- Test 5: Duplicate code (409) ---"
run_test "Duplicate site code" 409 \
    -s -X POST "$SITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Another Site","code":"HN-001"}'
echo ""

# ── Test 6: Missing name (400) ────────────────────────────────────────────────
echo "--- Test 6: Missing name (400) ---"
run_test "Missing name" 400 \
    -s -X POST "$SITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"code":"XYZ-001"}'
echo ""

# ── Test 7: Name too long (400) ───────────────────────────────────────────────
echo "--- Test 7: Name too long (400) ---"
LONG_NAME=$(python3 -c "print('A'*101)")
run_test "Name too long" 400 \
    -s -X POST "$SITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"$LONG_NAME\"}"
echo ""

# ── Test 8: Invalid code characters (400) ────────────────────────────────────
echo "--- Test 8: Invalid code characters (400) ---"
run_test "Invalid code characters" 400 \
    -s -X POST "$SITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Valid Name","code":"CODE WITH SPACES"}'
echo ""

# ── Test 9: Latitude out of range (400) ───────────────────────────────────────
echo "--- Test 9: Latitude out of range (400) ---"
run_test "Latitude out of range" 400 \
    -s -X POST "$SITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Bad Coords","latitude":200.0,"longitude":105.0}'
echo ""

# ── Test 10: Longitude out of range (400) ─────────────────────────────────────
echo "--- Test 10: Longitude out of range (400) ---"
run_test "Longitude out of range" 400 \
    -s -X POST "$SITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Bad Coords 2","latitude":21.0,"longitude":200.0}'
echo ""

# ── Test 11: Unauthenticated (401) ────────────────────────────────────────────
echo "--- Test 11: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X POST "$SITE_URL" \
    -H "Content-Type: application/json" \
    -d '{"name":"Stealth Site"}'
echo ""

# ── Test 12: Non-existent tenant (404) ───────────────────────────────────────
echo "--- Test 12: Non-existent tenant (404) ---"
run_test "Non-existent tenant" 404 \
    -s -X POST "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/sites" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Ghost Site"}'
echo ""

# ── Test 13: GET site (200) ───────────────────────────────────────────────────
if [ -n "${SITE_ID:-}" ]; then
    echo "--- Test 13: Get created site (200) ---"
    run_test "Get site" 200 \
        -s -X GET "$SITE_URL/$SITE_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN"
    echo ""
fi

# ── Test 14: GET non-existent site (404) ──────────────────────────────────────
echo "--- Test 14: Get non-existent site (404) ---"
run_test "Get non-existent site" 404 \
    -s -X GET "$SITE_URL/00000000-0000-0000-0000-000000000000" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Summary ──────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
