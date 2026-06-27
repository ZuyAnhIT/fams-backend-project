#!/usr/bin/env bash
# Tests for PUT /api/v1/tenants/{tenantId}/sites/{siteId}/shifts/{shiftId}
# Usage: BASE_URL=http://localhost:8080 bash test_update_shift.sh

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

echo "=== Update Shift Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: login ──────────────────────────────────────────────────────────────
echo "--- Setup: Login ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then
    echo "SETUP FAILED: Could not login"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."

# ── Setup: tenant + site + 2 shifts ──────────────────────────────────────────
echo "--- Setup: Create tenant, site, and shifts ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Upd Shift Corp ${TS}\",\"slug\":\"upd-shift-${TS}\"}")
t_body=$(echo "$t_resp" | head -n -1)
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Main Site","timezone":"UTC"}')
s_body=$(echo "$s_resp" | head -n -1)
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
SHIFT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts"

# Shift A — the one we'll update
sh_a=$(curl -s -X POST "$SHIFT_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Morning Shift","startTime":"08:00","endTime":"17:00"}')
SHIFT_A=$(echo "$sh_a" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Shift B — used for duplicate name tests
sh_b=$(curl -s -X POST "$SHIFT_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Afternoon Shift","startTime":"13:00","endTime":"22:00"}')
SHIFT_B=$(echo "$sh_b" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup: tenantId=$TENANT_ID siteId=$SITE_ID shiftA=$SHIFT_A shiftB=$SHIFT_B"
echo ""

# ── Test 1: Update name (200) ─────────────────────────────────────────────────
echo "--- Test 1: Update name (200) ---"
upd=$(curl -s -w "\n%{http_code}" \
    -X PUT "$SHIFT_URL/$SHIFT_A" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Early Morning Shift"}')
upd_body=$(echo "$upd" | head -n -1)
upd_status=$(echo "$upd" | tail -n 1)
if [ "$upd_status" -eq 200 ]; then
    new_name=$(echo "$upd_body" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "PASS: Update name (HTTP 200, name=$new_name)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Update name — expected 200, got $upd_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Update startTime (200) ───────────────────────────────────────────
echo "--- Test 2: Update startTime (200) ---"
upd2=$(curl -s -w "\n%{http_code}" \
    -X PUT "$SHIFT_URL/$SHIFT_A" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"startTime":"07:00"}')
upd2_body=$(echo "$upd2" | head -n -1)
upd2_status=$(echo "$upd2" | tail -n 1)
if [ "$upd2_status" -eq 200 ]; then
    new_start=$(echo "$upd2_body" | grep -o '"startTime":"[^"]*"' | cut -d'"' -f4)
    echo "PASS: Update startTime (HTTP 200, startTime=$new_start)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Update startTime — expected 200, got $upd2_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Update endTime (200) ─────────────────────────────────────────────
echo "--- Test 3: Update endTime (200) ---"
run_test "Update endTime" 200 \
    -s -X PUT "$SHIFT_URL/$SHIFT_A" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"endTime":"16:00"}'
echo ""

# ── Test 4: Update allowOvernight (200) ──────────────────────────────────────
echo "--- Test 4: Update allowOvernight (200) ---"
run_test "Update allowOvernight" 200 \
    -s -X PUT "$SHIFT_URL/$SHIFT_A" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"allowOvernight":true}'
echo ""

# ── Test 5: Deactivate shift (200) ───────────────────────────────────────────
echo "--- Test 5: Deactivate shift (status=inactive) (200) ---"
deact=$(curl -s -w "\n%{http_code}" \
    -X PUT "$SHIFT_URL/$SHIFT_A" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"inactive"}')
deact_body=$(echo "$deact" | head -n -1)
deact_status=$(echo "$deact" | tail -n 1)
if [ "$deact_status" -eq 200 ]; then
    s_val=$(echo "$deact_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "PASS: Deactivate (HTTP 200, status=$s_val)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Deactivate — expected 200, got $deact_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Inactive shift excluded from active list ─────────────────────────
echo "--- Test 6: Inactive shift excluded from status=active filter ---"
active_resp=$(curl -s -X GET "$SHIFT_URL?status=active" -H "Authorization: Bearer $ADMIN_TOKEN")
active_total=$(echo "$active_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
if [ "$active_total" = "1" ]; then
    echo "PASS: Only 1 active shift remains (totalElements=$active_total)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 1 active shift, got $active_total"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Restore shift to active (200) ────────────────────────────────────
echo "--- Test 7: Restore shift to active (200) ---"
run_test "Restore active" 200 \
    -s -X PUT "$SHIFT_URL/$SHIFT_A" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"active"}'
echo ""

# ── Test 8: Duplicate name rejected (409) ────────────────────────────────────
echo "--- Test 8: Duplicate name (409) ---"
run_test "Duplicate name" 409 \
    -s -X PUT "$SHIFT_URL/$SHIFT_A" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Afternoon Shift"}'
echo ""

# ── Test 9: Case-insensitive duplicate name rejected (409) ───────────────────
echo "--- Test 9: Case-insensitive duplicate name (409) ---"
run_test "Case-insensitive duplicate" 409 \
    -s -X PUT "$SHIFT_URL/$SHIFT_A" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"AFTERNOON SHIFT"}'
echo ""

# ── Test 10: Same name on same shift is allowed (200) ────────────────────────
echo "--- Test 10: Updating to own name allowed (200) ---"
run_test "Same name self" 200 \
    -s -X PUT "$SHIFT_URL/$SHIFT_A" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Early Morning Shift"}'
echo ""

# ── Test 11: Invalid status value (400) ──────────────────────────────────────
echo "--- Test 11: Invalid status value (400) ---"
run_test "Invalid status" 400 \
    -s -X PUT "$SHIFT_URL/$SHIFT_A" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"archived"}'
echo ""

# ── Test 12: Non-existent shift (404) ────────────────────────────────────────
echo "--- Test 12: Non-existent shift (404) ---"
run_test "Non-existent shift" 404 \
    -s -X PUT "$SHIFT_URL/00000000-0000-0000-0000-000000000000" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Ghost"}'
echo ""

# ── Test 13: Non-existent site (404) ─────────────────────────────────────────
echo "--- Test 13: Non-existent site (404) ---"
run_test "Non-existent site" 404 \
    -s -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/00000000-0000-0000-0000-000000000000/shifts/$SHIFT_A" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Ghost"}'
echo ""

# ── Test 14: Unauthenticated (401) ───────────────────────────────────────────
echo "--- Test 14: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X PUT "$SHIFT_URL/$SHIFT_A" \
    -H "Content-Type: application/json" \
    -d '{"name":"Stealth"}'
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
