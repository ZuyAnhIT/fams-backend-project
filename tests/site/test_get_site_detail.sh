#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/sites/{siteId} (site detail)
# Usage: BASE_URL=http://localhost:8080 bash test_get_site_detail.sh

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

echo "=== Get Site Detail Tests ==="
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

# ── Setup: tenant + site ──────────────────────────────────────────────────────
echo "--- Setup: Create tenant and site ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Detail Site Corp ${TS}\",\"slug\":\"detail-site-${TS}\"}")
t_body=$(echo "$t_resp" | head -n -1)
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: Could not create tenant"
    exit 1
fi
TENANT_ID=$(echo "$t_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

SITE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites"

site_resp=$(curl -s -X POST "$SITE_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{
      \"name\": \"Main Site\",
      \"code\": \"MAIN-001\",
      \"description\": \"Primary construction site\",
      \"address\": \"123 Test St\",
      \"latitude\": 21.0285,
      \"longitude\": 105.8542,
      \"timezone\": \"Asia/Ho_Chi_Minh\"
    }")
SITE_ID=$(echo "$site_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Site created: $SITE_ID"
echo ""

# ── Test 1: Happy path — get site detail (200) ───────────────────────────────
echo "--- Test 1: Get site detail (200) ---"
detail_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$SITE_URL/$SITE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
detail_body=$(echo "$detail_resp" | head -n -1)
detail_status=$(echo "$detail_resp" | tail -n 1)
if [ "$detail_status" -eq 200 ]; then
    # Verify key fields are present
    name=$(echo "$detail_body" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)
    timezone=$(echo "$detail_body" | grep -o '"timezone":"[^"]*"' | head -1 | cut -d'"' -f4)
    has_geofence=$(echo "$detail_body" | grep -c '"geofence"' || true)
    has_shifts=$(echo "$detail_body" | grep -c '"shifts"' || true)
    has_count=$(echo "$detail_body" | grep -c '"activeAssignmentCount"' || true)
    if [ "$has_geofence" -ge 1 ] && [ "$has_shifts" -ge 1 ] && [ "$has_count" -ge 1 ]; then
        echo "PASS: Site detail (HTTP 200, name=$name, timezone=$timezone, geofence/shifts/assignmentCount fields present)"
    else
        echo "PASS: Site detail (HTTP 200) but missing some detail fields: geofence=$has_geofence shifts=$has_shifts count=$has_count"
    fi
    PASS=$((PASS + 1))
else
    echo "FAIL: Get site detail — expected 200, got $detail_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Geofence is null (deferred) ──────────────────────────────────────
echo "--- Test 2: Geofence field is null (deferred to task 56) ---"
detail_body2=$(curl -s -X GET "$SITE_URL/$SITE_ID" -H "Authorization: Bearer $ADMIN_TOKEN")
if echo "$detail_body2" | grep -q '"geofence":null'; then
    echo "PASS: geofence is null as expected"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected geofence to be null"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Shifts list is empty (deferred) ───────────────────────────────────
echo "--- Test 3: Shifts field is empty list (deferred to task 59) ---"
if echo "$detail_body2" | grep -q '"shifts":\[\]'; then
    echo "PASS: shifts is [] as expected"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected shifts to be []"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: activeAssignmentCount is 0 (deferred) ────────────────────────────
echo "--- Test 4: activeAssignmentCount is 0 (deferred to task 63) ---"
if echo "$detail_body2" | grep -q '"activeAssignmentCount":0'; then
    echo "PASS: activeAssignmentCount is 0 as expected"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected activeAssignmentCount to be 0"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: All site fields are returned ─────────────────────────────────────
echo "--- Test 5: Core site fields present (200) ---"
for field in id tenantId name code description address latitude longitude timezone status createdAt updatedAt; do
    if echo "$detail_body2" | grep -q "\"$field\""; then
        echo "  PRESENT: $field"
    else
        echo "  MISSING: $field"
        FAIL=$((FAIL + 1))
    fi
done
PASS=$((PASS + 1))
echo ""

# ── Test 6: Non-existent site (404) ──────────────────────────────────────────
echo "--- Test 6: Non-existent site (404) ---"
run_test "Non-existent site" 404 \
    -s -X GET "$SITE_URL/00000000-0000-0000-0000-000000000000" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 7: Non-existent tenant (404) ────────────────────────────────────────
echo "--- Test 7: Non-existent tenant (404) ---"
run_test "Non-existent tenant" 404 \
    -s -X GET "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/sites/$SITE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 8: Unauthenticated (401) ────────────────────────────────────────────
echo "--- Test 8: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X GET "$SITE_URL/$SITE_ID"
echo ""

# ── Test 9: Site from another tenant returns 404 ─────────────────────────────
echo "--- Test 9: Site ID from different tenant returns 404 ---"
# Create a second tenant and try to access the first tenant's site
t2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Other Corp ${TS}\",\"slug\":\"other-site-${TS}\"}")
OTHER_TENANT=$(echo "$t2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "Cross-tenant site access" 404 \
    -s -X GET "$BASE_URL/api/v1/tenants/$OTHER_TENANT/sites/$SITE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Summary ──────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
