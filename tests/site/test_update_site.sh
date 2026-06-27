#!/usr/bin/env bash
# Tests for PUT /api/v1/tenants/{tenantId}/sites/{siteId} (update site)
# Usage: BASE_URL=http://localhost:8080 bash test_update_site.sh

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

echo "=== Update Site Tests ==="
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

# ── Setup: tenant + sites ─────────────────────────────────────────────────────
echo "--- Setup: Create tenant and sites ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Update Site Corp ${TS}\",\"slug\":\"upd-site-${TS}\"}")
t_body=$(echo "$t_resp" | head -n -1)
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
SITE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites"

# Site A — the one we'll update
s_a=$(curl -s -X POST "$SITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Alpha Site\",\"code\":\"A-001\",\"address\":\"Hanoi\",\"latitude\":21.0,\"longitude\":105.0,\"timezone\":\"UTC\"}")
SITE_A=$(echo "$s_a" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Site B — used to test duplicate-name conflicts
s_b=$(curl -s -X POST "$SITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Beta Site\",\"code\":\"B-001\"}")
SITE_B=$(echo "$s_b" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Sites: Alpha=$SITE_A Beta=$SITE_B"
echo ""

# ── Test 1: Update name (200) ─────────────────────────────────────────────────
echo "--- Test 1: Update name (200) ---"
upd=$(curl -s -w "\n%{http_code}" \
    -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Alpha Site Phase 2"}')
upd_body=$(echo "$upd" | head -n -1)
upd_status=$(echo "$upd" | tail -n 1)
if [ "$upd_status" -eq 200 ]; then
    new_name=$(echo "$upd_body" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "PASS: Update name (HTTP 200, new name=$new_name)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Update name — expected 200, got $upd_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Update code (200) ─────────────────────────────────────────────────
echo "--- Test 2: Update code (200) ---"
run_test "Update code" 200 \
    -s -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"code":"A-002"}'
echo ""

# ── Test 3: Clear code (200) ──────────────────────────────────────────────────
echo "--- Test 3: Clear code with clearCode=true (200) ---"
clr=$(curl -s -w "\n%{http_code}" \
    -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"clearCode":true}')
clr_body=$(echo "$clr" | head -n -1)
clr_status=$(echo "$clr" | tail -n 1)
if [ "$clr_status" -eq 200 ]; then
    code_val=$(echo "$clr_body" | grep -o '"code":[^,}]*' | head -1 | cut -d: -f2)
    echo "PASS: Clear code (HTTP 200, code=$code_val)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Clear code — expected 200, got $clr_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Update description (200) ─────────────────────────────────────────
echo "--- Test 4: Update description (200) ---"
run_test "Update description" 200 \
    -s -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"description":"Updated description for Alpha Site"}'
echo ""

# ── Test 5: Update address (200) ─────────────────────────────────────────────
echo "--- Test 5: Update address (200) ---"
run_test "Update address" 200 \
    -s -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"address":"456 New Street, Hanoi"}'
echo ""

# ── Test 6: Update coordinates (200) ─────────────────────────────────────────
echo "--- Test 6: Update latitude/longitude (200) ---"
run_test "Update coordinates" 200 \
    -s -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"latitude":10.7769,"longitude":106.7009}'
echo ""

# ── Test 7: Update timezone (200) ────────────────────────────────────────────
echo "--- Test 7: Update timezone (200) ---"
run_test "Update timezone" 200 \
    -s -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"timezone":"Asia/Ho_Chi_Minh"}'
echo ""

# ── Test 8: Update status to inactive (200) ───────────────────────────────────
echo "--- Test 8: Update status to inactive (200) ---"
run_test "Update status inactive" 200 \
    -s -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"inactive"}'
echo ""

# ── Test 9: Restore status to active (200) ────────────────────────────────────
echo "--- Test 9: Restore status to active (200) ---"
run_test "Restore status active" 200 \
    -s -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"active"}'
echo ""

# ── Test 10: Duplicate name (409) ─────────────────────────────────────────────
echo "--- Test 10: Duplicate name (409) ---"
run_test "Duplicate name rejected" 409 \
    -s -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Beta Site"}'
echo ""

# ── Test 11: Case-insensitive duplicate name (409) ────────────────────────────
echo "--- Test 11: Case-insensitive duplicate name (409) ---"
run_test "Case-insensitive duplicate name" 409 \
    -s -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"BETA SITE"}'
echo ""

# ── Test 12: Duplicate code (409) ─────────────────────────────────────────────
echo "--- Test 12: Duplicate code (409) ---"
run_test "Duplicate code rejected" 409 \
    -s -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"code":"B-001"}'
echo ""

# ── Test 13: Invalid status (400) ─────────────────────────────────────────────
echo "--- Test 13: Invalid status value (400) ---"
run_test "Invalid status" 400 \
    -s -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"archived"}'
echo ""

# ── Test 14: Invalid code characters (400) ────────────────────────────────────
echo "--- Test 14: Invalid code characters (400) ---"
run_test "Invalid code characters" 400 \
    -s -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"code":"BAD CODE!"}'
echo ""

# ── Test 15: Latitude out of range (400) ──────────────────────────────────────
echo "--- Test 15: Latitude out of range (400) ---"
run_test "Latitude out of range" 400 \
    -s -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"latitude":999.0}'
echo ""

# ── Test 16: Non-existent site (404) ─────────────────────────────────────────
echo "--- Test 16: Non-existent site (404) ---"
run_test "Non-existent site" 404 \
    -s -X PUT "$SITE_URL/00000000-0000-0000-0000-000000000000" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Ghost"}'
echo ""

# ── Test 17: Unauthenticated (401) ────────────────────────────────────────────
echo "--- Test 17: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X PUT "$SITE_URL/$SITE_A" \
    -H "Content-Type: application/json" \
    -d '{"name":"Stealth"}'
echo ""

# ── Summary ──────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
