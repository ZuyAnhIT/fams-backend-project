#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/sites/{siteId}/geofences (create geofence)
# and  GET  /api/v1/tenants/{tenantId}/sites/{siteId}/geofences/active
# Usage: BASE_URL=http://localhost:8080 bash test_create_geofence.sh

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

echo "=== Create Geofence Tests ==="
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

# ── Setup: tenant + site ──────────────────────────────────────────────────────
echo "--- Setup: Create tenant and site ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Geofence Corp ${TS}\",\"slug\":\"geo-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
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
    -d "{\"name\":\"Main Site\",\"code\":\"MS-001\",\"latitude\":21.0,\"longitude\":105.0,\"timezone\":\"UTC\"}")
s_body=$(echo "$s_resp" | head -n -1)
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create site"
    exit 1
fi
SITE_ID=$(echo "$s_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Second tenant for cross-tenant isolation tests
t2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Other Corp ${TS}\",\"slug\":\"other-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
TENANT2_ID=$(echo "$t2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

GEO_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/geofences"
POLY='[[105.0,21.0],[106.0,21.0],[106.0,22.0],[105.0,22.0],[105.0,21.0]]'
echo "Setup complete: tenantId=$TENANT_ID siteId=$SITE_ID"
echo ""

# ── Test 1: Create geofence (201) ─────────────────────────────────────────────
echo "--- Test 1: Create geofence (201) ---"
geo_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$GEO_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"coordinates\":$POLY,\"bufferMeters\":50}")
geo_body=$(echo "$geo_resp" | head -n -1)
geo_status=$(echo "$geo_resp" | tail -n 1)
if [ "$geo_status" -eq 201 ]; then
    GEO_ID=$(echo "$geo_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    geo_status_val=$(echo "$geo_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "PASS: Create geofence (HTTP 201, id=$GEO_ID, status=$geo_status_val)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Create geofence — expected 201, got $geo_status body=$geo_body"
    GEO_ID=""
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Active geofence appears in site detail ────────────────────────────
echo "--- Test 2: Geofence appears in site detail ---"
detail_resp=$(curl -s \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
if echo "$detail_resp" | grep -q '"geofence"'; then
    geo_in_detail=$(echo "$detail_resp" | grep -o '"geofence":{[^}]*}' | head -1)
    echo "PASS: Geofence in site detail ($geo_in_detail)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Geofence not found in site detail — $detail_resp"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: GET active geofence (200) ─────────────────────────────────────────
echo "--- Test 3: GET active geofence (200) ---"
run_test "Get active geofence" 200 \
    -s -X GET "$GEO_URL/active" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 4: Create second geofence supersedes first (201) ─────────────────────
echo "--- Test 4: Second geofence supersedes first (201) ---"
POLY2='[[104.0,20.0],[107.0,20.0],[107.0,23.0],[104.0,23.0],[104.0,20.0]]'
geo2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$GEO_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"coordinates\":$POLY2,\"bufferMeters\":100}")
geo2_body=$(echo "$geo2_resp" | head -n -1)
geo2_status=$(echo "$geo2_resp" | tail -n 1)
if [ "$geo2_status" -eq 201 ]; then
    GEO2_ID=$(echo "$geo2_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "PASS: Second geofence created (HTTP 201, id=$GEO2_ID)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Second geofence — expected 201, got $geo2_status"
    GEO2_ID=""
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Active geofence is now the second one ─────────────────────────────
echo "--- Test 5: Active geofence is the newest version ---"
active_resp=$(curl -s \
    -X GET "$GEO_URL/active" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
active_id=$(echo "$active_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -n "$GEO2_ID" ] && [ "$active_id" = "$GEO2_ID" ]; then
    echo "PASS: Active geofence is the newest (id=$active_id)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Active geofence mismatch — expected $GEO2_ID, got $active_id"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Missing coordinates (400) ────────────────────────────────────────
echo "--- Test 6: Missing coordinates (400) ---"
run_test "Missing coordinates" 400 \
    -s -X POST "$GEO_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"bufferMeters":50}'
echo ""

# ── Test 7: Too few points (400) ─────────────────────────────────────────────
echo "--- Test 7: Fewer than 4 coordinate pairs (400) ---"
run_test "Too few points" 400 \
    -s -X POST "$GEO_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"coordinates":[[105.0,21.0],[106.0,21.0],[105.0,21.0]]}'
echo ""

# ── Test 8: Negative bufferMeters (400) ──────────────────────────────────────
echo "--- Test 8: Negative bufferMeters (400) ---"
run_test "Negative buffer" 400 \
    -s -X POST "$GEO_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"coordinates\":$POLY,\"bufferMeters\":-10}"
echo ""

# ── Test 9: Non-existent site (404) ──────────────────────────────────────────
echo "--- Test 9: Non-existent site (404) ---"
run_test "Non-existent site" 404 \
    -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/00000000-0000-0000-0000-000000000000/geofences" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"coordinates\":$POLY}"
echo ""

# ── Test 10: Non-existent tenant (404) ───────────────────────────────────────
echo "--- Test 10: Non-existent tenant (404) ---"
run_test "Non-existent tenant" 404 \
    -s -X POST "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/sites/$SITE_ID/geofences" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"coordinates\":$POLY}"
echo ""

# ── Test 11: Cross-tenant isolation (404) ────────────────────────────────────
echo "--- Test 11: Cross-tenant isolation — site under wrong tenant (404) ---"
run_test "Cross-tenant site lookup" 404 \
    -s -X POST "$BASE_URL/api/v1/tenants/$TENANT2_ID/sites/$SITE_ID/geofences" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"coordinates\":$POLY}"
echo ""

# ── Test 12: No active geofence → 404 ────────────────────────────────────────
echo "--- Test 12: GET active geofence — site with no geofence (404) ---"
s2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Empty Site\",\"code\":\"ES-001\"}")
SITE2_ID=$(echo "$s2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "No geofence yet" 404 \
    -s -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE2_ID/geofences/active" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 13: Unauthenticated (401) ───────────────────────────────────────────
echo "--- Test 13: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X POST "$GEO_URL" \
    -H "Content-Type: application/json" \
    -d "{\"coordinates\":$POLY}"
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
