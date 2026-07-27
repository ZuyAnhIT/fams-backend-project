#!/usr/bin/env bash
# Tests for PUT /api/v1/tenants/{tenantId}/sites/{siteId}/shifts/{shiftId}/ot-config
# Usage: BASE_URL=http://localhost:8080 bash test_shift_ot_config.sh

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

echo "=== Shift OT Config Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: login ──────────────────────────────────────────────────────────────
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

# ── Setup: tenant + site + shift ──────────────────────────────────────────────
echo "--- Setup: Create tenant, site, and shift ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"OT Corp ${TS}\",\"slug\":\"ot-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
t_body=$(echo "$t_resp" | head -n -1)
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"planId":"fc259250-bf91-4341-907e-00fa84587c38"}'  # bump trial->enterprise so site-limit (1) does not block multi-site tests

s_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Main Site\",\"timezone\":\"UTC\"}")
s_body=$(echo "$s_resp" | head -n -1)
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create site"
    exit 1
fi
SITE_ID=$(echo "$s_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Morning Shift","startTime":"08:00","endTime":"17:00"}')
sh_body=$(echo "$sh_resp" | head -n -1)
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create shift"
    exit 1
fi
SHIFT_ID=$(echo "$sh_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
OT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts/$SHIFT_ID/ot-config"
echo "Setup complete: tenantId=$TENANT_ID siteId=$SITE_ID shiftId=$SHIFT_ID"
echo ""

# ── Test 1: Defaults are 0 / false after creation ────────────────────────────
echo "--- Test 1: New shift has OT fields defaulting to 0/false ---"
ot_val=$(echo "$sh_body" | grep -o '"allowOvertime":[a-z]*' | cut -d: -f2)
early=$(echo "$sh_body" | grep -o '"earlyCheckinMinutes":[0-9]*' | cut -d: -f2)
late=$(echo "$sh_body" | grep -o '"lateCheckoutMinutes":[0-9]*' | cut -d: -f2)
if [ "$ot_val" = "false" ] && [ "$early" = "0" ] && [ "$late" = "0" ]; then
    echo "PASS: Defaults correct (allowOvertime=$ot_val, early=$early, late=$late)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Unexpected defaults (allowOvertime=$ot_val, early=$early, late=$late)"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Configure allowOvertime only (200) ───────────────────────────────
echo "--- Test 2: Configure allowOvertime only (200) ---"
upd1=$(curl -s -w "\n%{http_code}" \
    -X PUT "$OT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"allowOvertime":true}')
upd1_body=$(echo "$upd1" | head -n -1)
upd1_status=$(echo "$upd1" | tail -n 1)
if [ "$upd1_status" -eq 200 ]; then
    ot_new=$(echo "$upd1_body" | grep -o '"allowOvertime":[a-z]*' | cut -d: -f2)
    early_new=$(echo "$upd1_body" | grep -o '"earlyCheckinMinutes":[0-9]*' | cut -d: -f2)
    echo "PASS: allowOvertime set (HTTP 200, allowOvertime=$ot_new, earlyCheckinMinutes=$early_new)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Configure allowOvertime — expected 200, got $upd1_status body=$upd1_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: earlyCheckinMinutes inherited (not reset) ────────────────────────
echo "--- Test 3: Other fields inherited when only one is updated ---"
early_after=$(echo "$upd1_body" | grep -o '"earlyCheckinMinutes":[0-9]*' | cut -d: -f2)
late_after=$(echo "$upd1_body" | grep -o '"lateCheckoutMinutes":[0-9]*' | cut -d: -f2)
if [ "$early_after" = "0" ] && [ "$late_after" = "0" ]; then
    echo "PASS: Other OT fields unchanged (early=$early_after, late=$late_after)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Unexpected field values (early=$early_after, late=$late_after)"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Configure earlyCheckinMinutes only (200) ─────────────────────────
echo "--- Test 4: Configure earlyCheckinMinutes only (200) ---"
run_test "Set earlyCheckinMinutes" 200 \
    -s -X PUT "$OT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"earlyCheckinMinutes":15}'
echo ""

# ── Test 5: Configure lateCheckoutMinutes only (200) ─────────────────────────
echo "--- Test 5: Configure lateCheckoutMinutes only (200) ---"
run_test "Set lateCheckoutMinutes" 200 \
    -s -X PUT "$OT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"lateCheckoutMinutes":30}'
echo ""

# ── Test 6: Configure all three fields (200) ─────────────────────────────────
echo "--- Test 6: Configure all three fields (200) ---"
upd_all=$(curl -s -w "\n%{http_code}" \
    -X PUT "$OT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"allowOvertime":true,"earlyCheckinMinutes":10,"lateCheckoutMinutes":20}')
upd_all_body=$(echo "$upd_all" | head -n -1)
upd_all_status=$(echo "$upd_all" | tail -n 1)
if [ "$upd_all_status" -eq 200 ]; then
    ot_f=$(echo "$upd_all_body" | grep -o '"allowOvertime":[a-z]*' | cut -d: -f2)
    ec_f=$(echo "$upd_all_body" | grep -o '"earlyCheckinMinutes":[0-9]*' | cut -d: -f2)
    lc_f=$(echo "$upd_all_body" | grep -o '"lateCheckoutMinutes":[0-9]*' | cut -d: -f2)
    echo "PASS: All fields (HTTP 200, ot=$ot_f, early=$ec_f, late=$lc_f)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Configure all — expected 200, got $upd_all_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Empty body — no fields provided (400) ────────────────────────────
echo "--- Test 7: No fields provided (400) ---"
run_test "No fields" 400 \
    -s -X PUT "$OT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{}'
echo ""

# ── Test 8: Negative earlyCheckinMinutes (400) ───────────────────────────────
echo "--- Test 8: Negative earlyCheckinMinutes (400) ---"
run_test "Negative early" 400 \
    -s -X PUT "$OT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"earlyCheckinMinutes":-5}'
echo ""

# ── Test 9: Negative lateCheckoutMinutes (400) ───────────────────────────────
echo "--- Test 9: Negative lateCheckoutMinutes (400) ---"
run_test "Negative late" 400 \
    -s -X PUT "$OT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"lateCheckoutMinutes":-1}'
echo ""

# ── Test 10: Non-existent shift (404) ────────────────────────────────────────
echo "--- Test 10: Non-existent shift (404) ---"
run_test "Non-existent shift" 404 \
    -s -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts/00000000-0000-0000-0000-000000000000/ot-config" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"allowOvertime":true}'
echo ""

# ── Test 11: Non-existent site (404) ─────────────────────────────────────────
echo "--- Test 11: Non-existent site (404) ---"
run_test "Non-existent site" 404 \
    -s -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/00000000-0000-0000-0000-000000000000/shifts/$SHIFT_ID/ot-config" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"allowOvertime":true}'
echo ""

# ── Test 12: Unauthenticated (401) ───────────────────────────────────────────
echo "--- Test 12: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X PUT "$OT_URL" \
    -H "Content-Type: application/json" \
    -d '{"allowOvertime":true}'
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
