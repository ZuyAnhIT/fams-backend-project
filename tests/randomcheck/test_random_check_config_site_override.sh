#!/usr/bin/env bash
# Tests for site-level random check configuration override
# POST/GET /api/v1/tenants/{tenantId}/random-check-configs/sites/{siteId}
# Usage: BASE_URL=http://localhost:8080 bash test_random_check_config_site_override.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    local actual_status
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Random Check Config — Site Override Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: admin login ────────────────────────────────────────────────────────
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: admin login (HTTP $login_status)"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."
echo ""

# ── Setup: tenant ─────────────────────────────────────────────────────────────
echo "--- Setup: Create tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"SiteOverride Corp ${TS}\",\"slug\":\"site-override-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant: $TENANT_ID"

# ── Setup: site ───────────────────────────────────────────────────────────────
echo "--- Setup: Create site ---"
s_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"High Risk Site ${TS}\",\"code\":\"HRS-${TS}\",\"address\":\"123 Main St\",\"timezone\":\"Asia/Ho_Chi_Minh\"}")
s_status=$(echo "$s_resp" | tail -n 1)
if [ "$s_status" -ne 201 ]; then echo "SETUP FAILED: site (HTTP $s_status)"; echo "$s_resp"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Site: $SITE_ID"
echo ""

VALID_PAYLOAD='{
  "checksPerShift": 3,
  "minIntervalMinutes": 45,
  "allowedStartTime": "07:00:00",
  "allowedEndTime": "18:00:00",
  "checkMode": "location_face",
  "applicableRoles": ["employee"],
  "responseWindowSeconds": 180
}'

FAKE_ID="00000000-0000-0000-0000-000000000000"

echo "--- Test 1: Create site override — should return 201 ---"
create_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/sites/$SITE_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "$VALID_PAYLOAD")
create_status=$(echo "$create_resp" | tail -n 1)
create_body=$(echo "$create_resp" | head -n -1)
if [ "$create_status" -eq 201 ]; then
    echo "PASS: Create site override (HTTP $create_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Create site override — expected 201, got $create_status"
    echo "Body: $create_body"
    FAIL=$((FAIL + 1))
fi
CONFIG_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Config ID: $CONFIG_ID"
echo ""

echo "--- Test 2: Duplicate site override — should return 409 ---"
run_test "Duplicate site override returns 409" 409 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/sites/$SITE_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "$VALID_PAYLOAD"

echo ""
echo "--- Test 3: Create override for non-existent site — should return 404 ---"
run_test "Non-existent site returns 404" 404 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/sites/$FAKE_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "$VALID_PAYLOAD"

echo ""
echo "--- Test 4: Create with invalid check_mode — should return 400 ---"
run_test "Invalid check_mode returns 400" 400 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/sites/$SITE_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 1,
      "minIntervalMinutes": 30,
      "allowedStartTime": "08:00:00",
      "allowedEndTime": "17:00:00",
      "checkMode": "bad_mode",
      "applicableRoles": ["employee"],
      "responseWindowSeconds": 120
    }'

echo ""
echo "--- Test 5: Unauthorized — should return 401 ---"
run_test "No auth token returns 401" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/sites/$SITE_ID" \
    -H "Content-Type: application/json" \
    -d "$VALID_PAYLOAD"

echo ""
echo "--- Test 6: Get site override — should return 200 ---"
get_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/sites/$SITE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
get_status=$(echo "$get_resp" | tail -n 1)
get_body=$(echo "$get_resp" | head -n -1)
if [ "$get_status" -eq 200 ]; then
    echo "PASS: Get site override (HTTP $get_status)"
    PASS=$((PASS + 1))
    # Verify the siteId is set in response
    returned_site=$(echo "$get_body" | grep -o '"siteId":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ "$returned_site" = "$SITE_ID" ]; then
        echo "PASS: Response siteId matches"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Response siteId mismatch — expected $SITE_ID, got $returned_site"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Get site override — expected 200, got $get_status"
    echo "Body: $get_body"
    FAIL=$((FAIL + 1))
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 7: Get site override for non-existent site — should return 404 ---"
run_test "Get override for unknown site returns 404" 404 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/sites/$FAKE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "--- Test 8: Site override appears in list — should return 200 ---"
run_test "List configs includes site override" 200 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "--- Test 9: Update the site override via PUT /{configId} — should return 200 ---"
run_test "Update site override returns 200" 200 \
    -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"checksPerShift": 5, "checkMode": "location_face_liveness"}'

echo ""
echo "--- Test 10: Delete the site override — should return 204 ---"
run_test "Delete site override returns 204" 204 \
    -X DELETE "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "--- Test 11: Get site override after delete — should return 404 ---"
run_test "Get site override after delete returns 404" 404 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/sites/$SITE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "--- Test 12: Re-create site override after delete — should return 201 ---"
run_test "Re-create site override after delete returns 201" 201 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/sites/$SITE_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "$VALID_PAYLOAD"

echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
