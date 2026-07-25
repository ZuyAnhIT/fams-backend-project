#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/sites/{siteId}/geofences (geofence history)
# Usage: BASE_URL=http://localhost:8080 bash test_geofence_history.sh

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

echo "=== Geofence History Tests ==="
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

# ── Setup: tenant + site + 3 geofence versions ───────────────────────────────
echo "--- Setup: Create tenant, site, and 3 geofence versions ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Geo History Corp ${TS}\",\"slug\":\"geo-hist-${TS}\"}")
t_body=$(echo "$t_resp" | head -n -1)
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"History Site\",\"latitude\":21.0,\"longitude\":105.0,\"timezone\":\"UTC\"}")
s_body=$(echo "$s_resp" | head -n -1)
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create site"
    exit 1
fi
SITE_ID=$(echo "$s_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

GEO_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/geofences"
POLY1='[[105.0,21.0],[106.0,21.0],[106.0,22.0],[105.0,22.0],[105.0,21.0]]'
POLY2='[[104.0,20.0],[107.0,20.0],[107.0,23.0],[104.0,23.0],[104.0,20.0]]'
POLY3='[[103.0,19.0],[108.0,19.0],[108.0,24.0],[103.0,24.0],[103.0,19.0]]'

# Create 3 geofence versions
curl -s -X POST "$GEO_URL" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"coordinates\":$POLY1,\"bufferMeters\":10}" > /dev/null
curl -s -X PUT "$GEO_URL/active" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"coordinates\":$POLY2,\"bufferMeters\":20}" > /dev/null
curl -s -X PUT "$GEO_URL/active" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"coordinates\":$POLY3,\"bufferMeters\":30}" > /dev/null

echo "Setup complete: tenantId=$TENANT_ID siteId=$SITE_ID (3 geofence versions created)"
echo ""

# ── Test 1: History returns all 3 versions (200) ─────────────────────────────
echo "--- Test 1: History returns all versions (200) ---"
hist_resp=$(curl -s \
    -X GET "$GEO_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
http_code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "$GEO_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
total=$(echo "$hist_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
if [ "$http_code" -eq 200 ] && [ "$total" = "3" ]; then
    echo "PASS: History returned (HTTP 200, totalElements=$total)"
    PASS=$((PASS + 1))
else
    echo "FAIL: History — expected 200 with 3 elements, got HTTP $http_code totalElements=$total"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Newest version is first (DESC order) ──────────────────────────────
echo "--- Test 2: Results are newest-first ---"
first_buffer=$(echo "$hist_resp" | grep -o '"bufferMeters":[0-9]*' | head -1 | cut -d: -f2)
if [ "$first_buffer" = "30" ]; then
    echo "PASS: Newest-first order (first bufferMeters=$first_buffer)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected first bufferMeters=30 (newest), got $first_buffer"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Active version is first in list ───────────────────────────────────
echo "--- Test 3: First record has status=active ---"
first_status=$(echo "$hist_resp" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ "$first_status" = "active" ]; then
    echo "PASS: First record is active (status=$first_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected first status=active, got $first_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Older versions have status=superseded ────────────────────────────
echo "--- Test 4: Older versions are superseded ---"
superseded_count=$(echo "$hist_resp" | grep -o '"status":"superseded"' | wc -l | tr -d ' ')
if [ "$superseded_count" = "2" ]; then
    echo "PASS: 2 superseded versions found"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 2 superseded versions, got $superseded_count"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Pagination — page size 2 ─────────────────────────────────────────
echo "--- Test 5: Pagination page size 2 ---"
page_resp=$(curl -s \
    -X GET "$GEO_URL?page=0&size=2" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
page_total=$(echo "$page_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
page_size=$(echo "$page_resp" | grep -o '"size":[0-9]*' | head -1 | cut -d: -f2)
total_pages=$(echo "$page_resp" | grep -o '"totalPages":[0-9]*' | cut -d: -f2)
if [ "$page_total" = "3" ] && [ "$page_size" = "2" ] && [ "$total_pages" = "2" ]; then
    echo "PASS: Pagination (totalElements=$page_total, size=$page_size, totalPages=$total_pages)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Pagination mismatch (totalElements=$page_total, size=$page_size, totalPages=$total_pages)"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Second page returns remaining record ──────────────────────────────
echo "--- Test 6: Second page has 1 record ---"
page2_resp=$(curl -s \
    -X GET "$GEO_URL?page=1&size=2" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
page2_items=$(echo "$page2_resp" | grep -o '"bufferMeters":[0-9]*' | wc -l | tr -d ' ')
if [ "$page2_items" = "1" ]; then
    echo "PASS: Second page has 1 record"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 1 record on page 2, got $page2_items"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Site with no geofences returns empty list (200) ──────────────────
echo "--- Test 7: Site with no geofences returns empty list (200) ---"
s2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Empty Site\"}")
SITE2_ID=$(echo "$s2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
empty_resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE2_ID/geofences" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
empty_total=$(echo "$empty_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
empty_status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE2_ID/geofences" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
if [ "$empty_status" -eq 200 ] && [ "$empty_total" = "0" ]; then
    echo "PASS: Empty list (HTTP 200, totalElements=0)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 200 with 0 elements, got HTTP $empty_status totalElements=$empty_total"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Non-existent site (404) ──────────────────────────────────────────
echo "--- Test 8: Non-existent site (404) ---"
run_test "Non-existent site" 404 \
    -s -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/00000000-0000-0000-0000-000000000000/geofences" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 9: Non-existent tenant (404) ────────────────────────────────────────
echo "--- Test 9: Non-existent tenant (404) ---"
run_test "Non-existent tenant" 404 \
    -s -X GET "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/sites/$SITE_ID/geofences" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 10: Unauthenticated (401) ───────────────────────────────────────────
echo "--- Test 10: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X GET "$GEO_URL"
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
