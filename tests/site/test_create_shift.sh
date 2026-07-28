#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/sites/{siteId}/shifts (create shift template)
# Usage: BASE_URL=http://localhost:8080 bash test_create_shift.sh

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

echo "=== Create Shift Tests ==="
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
ENTERPRISE_PLAN_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "SELECT id FROM plans WHERE name='enterprise';" | tr -d " \n")
echo "Admin token obtained."

# ── Setup: tenant + site ──────────────────────────────────────────────────────
echo "--- Setup: Create tenant and site ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Shift Corp ${TS}\",\"slug\":\"shift-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
t_body=$(echo "$t_resp" | head -n -1)
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" -d "{\"planId\":\"$ENTERPRISE_PLAN_ID\"}"  # bump trial->enterprise so site-limit (1) does not block multi-site tests

s_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Main Site\",\"timezone\":\"Asia/Ho_Chi_Minh\"}")
s_body=$(echo "$s_resp" | head -n -1)
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create site"
    exit 1
fi
SITE_ID=$(echo "$s_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
SHIFT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts"
echo "Setup complete: tenantId=$TENANT_ID siteId=$SITE_ID"
echo ""

# ── Test 1: Create morning shift (201) ───────────────────────────────────────
echo "--- Test 1: Create morning shift (201) ---"
s1_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$SHIFT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Morning Shift","startTime":"08:00","endTime":"17:00"}')
s1_body=$(echo "$s1_resp" | head -n -1)
s1_status=$(echo "$s1_resp" | tail -n 1)
if [ "$s1_status" -eq 201 ]; then
    SHIFT_ID=$(echo "$s1_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    s_name=$(echo "$s1_body" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)
    s_start=$(echo "$s1_body" | grep -o '"startTime":"[^"]*"' | cut -d'"' -f4)
    s_end=$(echo "$s1_body" | grep -o '"endTime":"[^"]*"' | cut -d'"' -f4)
    echo "PASS: Morning shift (HTTP 201, name=$s_name, start=$s_start, end=$s_end)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Morning shift — expected 201, got $s1_status body=$s1_body"
    SHIFT_ID=""
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Create overnight shift with allowOvernight=true (201) ─────────────
echo "--- Test 2: Create overnight shift (201) ---"
s2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$SHIFT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Night Shift","startTime":"22:00","endTime":"06:00","allowOvernight":true}')
s2_body=$(echo "$s2_resp" | head -n -1)
s2_status=$(echo "$s2_resp" | tail -n 1)
if [ "$s2_status" -eq 201 ]; then
    overnight=$(echo "$s2_body" | grep -o '"allowOvernight":[a-z]*' | cut -d: -f2)
    echo "PASS: Night shift (HTTP 201, allowOvernight=$overnight)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Night shift — expected 201, got $s2_status body=$s2_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Shift appears in site detail ─────────────────────────────────────
echo "--- Test 3: Shifts appear in site detail ---"
detail_resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
shift_count=$(echo "$detail_resp" | grep -o '"startTime"' | wc -l | tr -d ' ')
if [ "$shift_count" = "2" ]; then
    echo "PASS: $shift_count shifts in site detail"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 2 shifts in site detail, got $shift_count"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: startTime format is HH:mm ────────────────────────────────────────
echo "--- Test 4: startTime serialised as HH:mm ---"
start_val=$(echo "$s1_body" | grep -o '"startTime":"[^"]*"' | cut -d'"' -f4)
if echo "$start_val" | grep -qE '^[0-9]{2}:[0-9]{2}$'; then
    echo "PASS: startTime format is HH:mm ($start_val)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Unexpected startTime format ($start_val)"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Duplicate name (case-insensitive) (409) ──────────────────────────
echo "--- Test 5: Duplicate shift name (409) ---"
run_test "Duplicate name" 409 \
    -s -X POST "$SHIFT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"morning shift","startTime":"09:00","endTime":"18:00"}'
echo ""

# ── Test 6: Missing name (400) ───────────────────────────────────────────────
echo "--- Test 6: Missing name (400) ---"
run_test "Missing name" 400 \
    -s -X POST "$SHIFT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"startTime":"08:00","endTime":"17:00"}'
echo ""

# ── Test 7: Missing startTime (400) ──────────────────────────────────────────
echo "--- Test 7: Missing startTime (400) ---"
run_test "Missing startTime" 400 \
    -s -X POST "$SHIFT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Afternoon Shift","endTime":"17:00"}'
echo ""

# ── Test 8: Missing endTime (400) ────────────────────────────────────────────
echo "--- Test 8: Missing endTime (400) ---"
run_test "Missing endTime" 400 \
    -s -X POST "$SHIFT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Afternoon Shift","startTime":"13:00"}'
echo ""

# ── Test 9: Same name is allowed on a different site (201) ───────────────────
echo "--- Test 9: Same name allowed on different site (201) ---"
s2_site_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Beta Site\"}")
SITE2_ID=$(echo "$s2_site_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "Same name different site" 201 \
    -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE2_ID/shifts" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Morning Shift","startTime":"08:00","endTime":"17:00"}'
echo ""

# ── Test 10: Non-existent site (404) ─────────────────────────────────────────
echo "--- Test 10: Non-existent site (404) ---"
run_test "Non-existent site" 404 \
    -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/00000000-0000-0000-0000-000000000000/shifts" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Ghost Shift","startTime":"08:00","endTime":"17:00"}'
echo ""

# ── Test 11: Non-existent tenant (404) ───────────────────────────────────────
echo "--- Test 11: Non-existent tenant (404) ---"
run_test "Non-existent tenant" 404 \
    -s -X POST "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Ghost Shift","startTime":"08:00","endTime":"17:00"}'
echo ""

# ── Test 12: Unauthenticated (401) ───────────────────────────────────────────
echo "--- Test 12: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X POST "$SHIFT_URL" \
    -H "Content-Type: application/json" \
    -d '{"name":"Anon Shift","startTime":"08:00","endTime":"17:00"}'
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
