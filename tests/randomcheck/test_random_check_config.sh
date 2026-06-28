#!/usr/bin/env bash
# Tests for random check configuration CRUD
# POST/GET/PUT/DELETE /api/v1/tenants/{tenantId}/random-check-configs/...
# Usage: BASE_URL=http://localhost:8080 bash test_random_check_config.sh

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

echo "=== Random Check Config Tests ==="
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

# ── Setup: create a tenant ────────────────────────────────────────────────────
echo "--- Setup: Create tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"RandCheck Corp ${TS}\",\"slug\":\"randcheck-corp-${TS}\"}")
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then echo "SETUP FAILED: create tenant (HTTP $t_status)"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: $TENANT_ID"
echo ""

# ── Payload ───────────────────────────────────────────────────────────────────
VALID_PAYLOAD='{
  "checksPerShift": 2,
  "minIntervalMinutes": 60,
  "allowedStartTime": "08:00:00",
  "allowedEndTime": "17:00:00",
  "checkMode": "location_only",
  "applicableRoles": ["supervisor", "employee"],
  "responseWindowSeconds": 300
}'

echo "--- Test 1: Create tenant-default config — should return 201 ---"
create_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "$VALID_PAYLOAD")
create_status=$(echo "$create_resp" | tail -n 1)
create_body=$(echo "$create_resp" | head -n -1)
if [ "$create_status" -eq 201 ]; then
    echo "PASS: Create tenant-default config (HTTP $create_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Create tenant-default config — expected 201, got $create_status"
    echo "Body: $create_body"
    FAIL=$((FAIL + 1))
fi
CONFIG_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Config ID: $CONFIG_ID"
echo ""

echo "--- Test 2: Duplicate create — should return 409 ---"
run_test "Duplicate tenant-default config returns 409" 409 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "$VALID_PAYLOAD"

echo ""
echo "--- Test 3: Create with invalid check_mode — should return 400 ---"
run_test "Invalid check_mode returns 400" 400 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 2,
      "minIntervalMinutes": 60,
      "allowedStartTime": "08:00:00",
      "allowedEndTime": "17:00:00",
      "checkMode": "invalid_mode",
      "applicableRoles": ["employee"],
      "responseWindowSeconds": 300
    }'

echo ""
echo "--- Test 4: Create with missing required fields — should return 400 ---"
run_test "Missing required fields returns 400" 400 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{}'

echo ""
echo "--- Test 5: Unauthorized (no token) — should return 401 ---"
run_test "No auth token returns 401" 401 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" \
    -d "$VALID_PAYLOAD"

echo ""
echo "--- Test 6: Get tenant-default config — should return 200 ---"
run_test "Get tenant-default config returns 200" 200 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "--- Test 7: Get tenant-default for non-existent tenant — should return 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000000"
run_test "Get default config for unknown tenant returns 404" 404 \
    -X GET "$BASE_URL/api/v1/tenants/$FAKE_ID/random-check-configs/tenant-default" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "--- Test 8: List all configs — should return 200 ---"
run_test "List configs returns 200" 200 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "--- Test 9: Get config by ID — should return 200 ---"
run_test "Get config by ID returns 200" 200 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "--- Test 10: Get non-existent config — should return 404 ---"
run_test "Get unknown config returns 404" 404 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$FAKE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "--- Test 11: Update config — should return 200 ---"
run_test "Update config returns 200" 200 \
    -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"checksPerShift": 3, "checkMode": "location_face", "isActive": false}'

echo ""
echo "--- Test 12: Update with invalid value — should return 400 ---"
run_test "Update with invalid checksPerShift (0) returns 400" 400 \
    -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"checksPerShift": 0}'

echo ""
echo "--- Test 13: Delete config — should return 204 ---"
run_test "Delete config returns 204" 204 \
    -X DELETE "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "--- Test 14: Get deleted config — should return 404 ---"
run_test "Get deleted config returns 404" 404 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "--- Test 15: Get tenant-default after delete — should return 404 ---"
run_test "Get tenant-default after delete returns 404" 404 \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "--- Test 16: Re-create after delete — should return 201 ---"
run_test "Re-create tenant-default after delete returns 201" 201 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "$VALID_PAYLOAD"

echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
