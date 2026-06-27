#!/usr/bin/env bash
# Tests for PUT /api/v1/tenants/{tenantId}/sites/{siteId}/geofences/active (update geofence)
# Usage: BASE_URL=http://localhost:8080 bash test_update_geofence.sh

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

echo "=== Update Geofence Tests ==="
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
    echo "SETUP FAILED: Could not login (HTTP $login_status)"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."

# ── Setup: tenant + site + initial geofence ───────────────────────────────────
echo "--- Setup: Create tenant, site, and initial geofence ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Update Geo Corp ${TS}\",\"slug\":\"upd-geo-${TS}\"}")
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
    -d "{\"name\":\"Alpha Site\",\"latitude\":21.0,\"longitude\":105.0,\"timezone\":\"UTC\"}")
s_body=$(echo "$s_resp" | head -n -1)
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create site"
    exit 1
fi
SITE_ID=$(echo "$s_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

GEO_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/geofences"
POLY='[[105.0,21.0],[106.0,21.0],[106.0,22.0],[105.0,22.0],[105.0,21.0]]'

# Create initial geofence
g_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$GEO_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"coordinates\":$POLY,\"bufferMeters\":50}")
g_body=$(echo "$g_resp" | head -n -1)
if [ "$(echo "$g_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create initial geofence"
    exit 1
fi
ORIG_ID=$(echo "$g_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Setup complete: tenantId=$TENANT_ID siteId=$SITE_ID originalGeofenceId=$ORIG_ID"
echo ""

# ── Test 1: Update coordinates only (200) ─────────────────────────────────────
echo "--- Test 1: Update coordinates only (200) ---"
POLY2='[[104.0,20.0],[107.0,20.0],[107.0,23.0],[104.0,23.0],[104.0,20.0]]'
upd1=$(curl -s -w "\n%{http_code}" \
    -X PUT "$GEO_URL/active" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"coordinates\":$POLY2}")
upd1_body=$(echo "$upd1" | head -n -1)
upd1_status=$(echo "$upd1" | tail -n 1)
if [ "$upd1_status" -eq 200 ]; then
    NEW_ID=$(echo "$upd1_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    buf=$(echo "$upd1_body" | grep -o '"bufferMeters":[0-9]*' | cut -d: -f2)
    echo "PASS: Update coordinates (HTTP 200, newId=$NEW_ID, bufferInherited=$buf)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Update coordinates — expected 200, got $upd1_status body=$upd1_body"
    NEW_ID=""
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: New ID differs from original (version created) ────────────────────
echo "--- Test 2: New version has a different ID ---"
if [ -n "$NEW_ID" ] && [ "$NEW_ID" != "$ORIG_ID" ]; then
    echo "PASS: New geofence version (oldId=$ORIG_ID newId=$NEW_ID)"
    PASS=$((PASS + 1))
else
    echo "FAIL: ID did not change (orig=$ORIG_ID new=$NEW_ID)"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Buffer inherited from previous version ────────────────────────────
echo "--- Test 3: Buffer inherited when only coordinates updated ---"
active_resp=$(curl -s \
    -X GET "$GEO_URL/active" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
buf_val=$(echo "$active_resp" | grep -o '"bufferMeters":[0-9]*' | cut -d: -f2)
if [ "$buf_val" = "50" ]; then
    echo "PASS: Buffer inherited (bufferMeters=$buf_val)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Buffer not inherited (expected 50, got $buf_val)"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Update buffer only (200) ─────────────────────────────────────────
echo "--- Test 4: Update buffer only (200) ---"
upd2=$(curl -s -w "\n%{http_code}" \
    -X PUT "$GEO_URL/active" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"bufferMeters":200}')
upd2_body=$(echo "$upd2" | head -n -1)
upd2_status=$(echo "$upd2" | tail -n 1)
if [ "$upd2_status" -eq 200 ]; then
    new_buf=$(echo "$upd2_body" | grep -o '"bufferMeters":[0-9]*' | cut -d: -f2)
    echo "PASS: Update buffer (HTTP 200, bufferMeters=$new_buf)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Update buffer — expected 200, got $upd2_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Update both coordinates and buffer (200) ─────────────────────────
echo "--- Test 5: Update both coordinates and buffer (200) ---"
POLY3='[[103.0,19.0],[108.0,19.0],[108.0,24.0],[103.0,24.0],[103.0,19.0]]'
run_test "Update both fields" 200 \
    -s -X PUT "$GEO_URL/active" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"coordinates\":$POLY3,\"bufferMeters\":0}"
echo ""

# ── Test 6: No fields provided (400) ─────────────────────────────────────────
echo "--- Test 6: Empty body — no fields provided (400) ---"
run_test "No fields" 400 \
    -s -X PUT "$GEO_URL/active" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{}'
echo ""

# ── Test 7: Fewer than 4 coordinate points (400) ─────────────────────────────
echo "--- Test 7: Fewer than 4 coordinate pairs (400) ---"
run_test "Too few points" 400 \
    -s -X PUT "$GEO_URL/active" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"coordinates":[[105.0,21.0],[106.0,21.0],[105.0,21.0]]}'
echo ""

# ── Test 8: Negative buffer (400) ────────────────────────────────────────────
echo "--- Test 8: Negative bufferMeters (400) ---"
run_test "Negative buffer" 400 \
    -s -X PUT "$GEO_URL/active" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"bufferMeters":-5}'
echo ""

# ── Test 9: Site with no geofence (404) ──────────────────────────────────────
echo "--- Test 9: No active geofence exists (404) ---"
s2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Empty Site\"}")
SITE2_ID=$(echo "$s2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "No active geofence" 404 \
    -s -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE2_ID/geofences/active" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"bufferMeters":10}'
echo ""

# ── Test 10: Non-existent site (404) ─────────────────────────────────────────
echo "--- Test 10: Non-existent site (404) ---"
run_test "Non-existent site" 404 \
    -s -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/00000000-0000-0000-0000-000000000000/geofences/active" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"bufferMeters":10}'
echo ""

# ── Test 11: Non-existent tenant (404) ───────────────────────────────────────
echo "--- Test 11: Non-existent tenant (404) ---"
run_test "Non-existent tenant" 404 \
    -s -X PUT "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/sites/$SITE_ID/geofences/active" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"bufferMeters":10}'
echo ""

# ── Test 12: Unauthenticated (401) ───────────────────────────────────────────
echo "--- Test 12: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X PUT "$GEO_URL/active" \
    -H "Content-Type: application/json" \
    -d '{"bufferMeters":10}'
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
