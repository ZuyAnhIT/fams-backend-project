#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/sites/{siteId}/shifts (list shifts)
# Usage: BASE_URL=http://localhost:8080 bash test_list_shifts.sh

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

echo "=== List Shifts Tests ==="
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

# ── Setup: tenant + site + 3 shifts ──────────────────────────────────────────
echo "--- Setup: Create tenant, site, and shifts ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"List Shift Corp ${TS}\",\"slug\":\"list-shift-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
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
SHIFT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts"

# Create 3 shifts: morning (active), afternoon (active), night (inactive via OT config update)
sh1=$(curl -s -X POST "$SHIFT_URL" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Morning Shift","startTime":"08:00","endTime":"12:00"}')
SH1_ID=$(echo "$sh1" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh2=$(curl -s -X POST "$SHIFT_URL" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Afternoon Shift","startTime":"13:00","endTime":"17:00"}')
SH2_ID=$(echo "$sh2" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh3=$(curl -s -X POST "$SHIFT_URL" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Night Shift","startTime":"22:00","endTime":"06:00","allowOvernight":true}')
SH3_ID=$(echo "$sh3" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup complete: tenantId=$TENANT_ID siteId=$SITE_ID sh1=$SH1_ID sh2=$SH2_ID sh3=$SH3_ID"
echo ""

# ── Test 1: List all shifts (200, 3 records) ──────────────────────────────────
echo "--- Test 1: List all shifts (200) ---"
list_resp=$(curl -s -X GET "$SHIFT_URL" -H "Authorization: Bearer $ADMIN_TOKEN")
http_code=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$SHIFT_URL" -H "Authorization: Bearer $ADMIN_TOKEN")
total=$(echo "$list_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
if [ "$http_code" -eq 200 ] && [ "$total" = "3" ]; then
    echo "PASS: List all (HTTP 200, totalElements=$total)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 200 with 3 elements, got HTTP $http_code totalElements=$total"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Sorted by startTime ascending ─────────────────────────────────────
echo "--- Test 2: Results sorted by startTime ASC ---"
first_start=$(echo "$list_resp" | grep -o '"startTime":"[^"]*"' | head -1 | cut -d'"' -f4)
last_start=$(echo "$list_resp" | grep -o '"startTime":"[^"]*"' | tail -1 | cut -d'"' -f4)
if [ "$first_start" = "08:00" ] && [ "$last_start" = "22:00" ]; then
    echo "PASS: Sorted ASC (first=$first_start, last=$last_start)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Unexpected order (first=$first_start, last=$last_start)"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Filter by status=active ──────────────────────────────────────────
echo "--- Test 3: Filter by status=active ---"
active_resp=$(curl -s -X GET "$SHIFT_URL?status=active" -H "Authorization: Bearer $ADMIN_TOKEN")
active_total=$(echo "$active_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
if [ "$active_total" = "3" ]; then
    echo "PASS: Active filter (totalElements=$active_total)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 3 active shifts, got $active_total"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Filter by status=inactive returns empty ──────────────────────────
echo "--- Test 4: Filter by status=inactive (empty list) ---"
inactive_resp=$(curl -s -X GET "$SHIFT_URL?status=inactive" -H "Authorization: Bearer $ADMIN_TOKEN")
inactive_total=$(echo "$inactive_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
if [ "$inactive_total" = "0" ]; then
    echo "PASS: Inactive filter returns empty (totalElements=$inactive_total)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 0 inactive shifts, got $inactive_total"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Pagination — page size 2 ─────────────────────────────────────────
echo "--- Test 5: Pagination page size 2 ---"
page_resp=$(curl -s -X GET "$SHIFT_URL?page=0&size=2" -H "Authorization: Bearer $ADMIN_TOKEN")
p_total=$(echo "$page_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
p_size=$(echo "$page_resp" | grep -o '"size":[0-9]*' | head -1 | cut -d: -f2)
p_pages=$(echo "$page_resp" | grep -o '"totalPages":[0-9]*' | cut -d: -f2)
if [ "$p_total" = "3" ] && [ "$p_size" = "2" ] && [ "$p_pages" = "2" ]; then
    echo "PASS: Pagination (totalElements=$p_total, size=$p_size, totalPages=$p_pages)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Pagination mismatch (totalElements=$p_total, size=$p_size, totalPages=$p_pages)"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Second page has 1 record ─────────────────────────────────────────
echo "--- Test 6: Second page has 1 record ---"
page2_resp=$(curl -s -X GET "$SHIFT_URL?page=1&size=2" -H "Authorization: Bearer $ADMIN_TOKEN")
page2_count=$(echo "$page2_resp" | grep -o '"startTime"' | wc -l | tr -d ' ')
if [ "$page2_count" = "1" ]; then
    echo "PASS: Second page has 1 record"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 1 record on page 2, got $page2_count"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: OT fields present in response ─────────────────────────────────────
echo "--- Test 7: OT fields present in list response ---"
has_ot=$(echo "$list_resp" | grep -c '"allowOvertime"' || true)
has_early=$(echo "$list_resp" | grep -c '"earlyCheckinMinutes"' || true)
has_late=$(echo "$list_resp" | grep -c '"lateCheckoutMinutes"' || true)
if [ "$has_ot" -gt 0 ] && [ "$has_early" -gt 0 ] && [ "$has_late" -gt 0 ]; then
    echo "PASS: OT fields present in response"
    PASS=$((PASS + 1))
else
    echo "FAIL: OT fields missing (allowOvertime=$has_ot, earlyCheckinMinutes=$has_early, lateCheckoutMinutes=$has_late)"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Site with no shifts returns empty list ────────────────────────────
echo "--- Test 8: Empty site returns empty list (200) ---"
s2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Empty Site"}')
SITE2_ID=$(echo "$s2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
empty_resp=$(curl -s -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE2_ID/shifts" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
empty_code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE2_ID/shifts" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
empty_total=$(echo "$empty_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
if [ "$empty_code" -eq 200 ] && [ "$empty_total" = "0" ]; then
    echo "PASS: Empty site (HTTP 200, totalElements=0)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 200 with 0, got HTTP $empty_code totalElements=$empty_total"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: Non-existent site (404) ──────────────────────────────────────────
echo "--- Test 9: Non-existent site (404) ---"
run_test "Non-existent site" 404 \
    -s -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/00000000-0000-0000-0000-000000000000/shifts" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 10: Non-existent tenant (404) ───────────────────────────────────────
echo "--- Test 10: Non-existent tenant (404) ---"
run_test "Non-existent tenant" 404 \
    -s -X GET "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/sites/$SITE_ID/shifts" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 11: Unauthenticated (401) ───────────────────────────────────────────
echo "--- Test 11: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X GET "$SHIFT_URL"
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
