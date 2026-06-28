#!/usr/bin/env bash
# Tests for check mode configuration (task 94)
# PUT /api/v1/tenants/{tenantId}/random-check-configs/{configId}/check-mode
# Usage: BASE_URL=http://localhost:8080 bash test_check_mode.sh

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

check_mode_value() {
    local label="$1"
    local config_id="$2"
    local expected_mode="$3"
    local resp body actual_mode status
    resp=$(curl -s -w "\n%{http_code}" \
        -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$config_id" \
        -H "Authorization: Bearer $ADMIN_TOKEN")
    status=$(echo "$resp" | tail -n 1)
    body=$(echo "$resp" | head -n -1)
    actual_mode=$(echo "$body" | grep -o '"checkMode":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ "$status" -eq 200 ] && [ "$actual_mode" = "$expected_mode" ]; then
        echo "PASS: $label — checkMode is '$actual_mode'"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $label — expected checkMode='$expected_mode', got '$actual_mode' (HTTP $status)"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Check Mode Configuration Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup: Login ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Token obtained."

TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"CheckMode Corp ${TS}\",\"slug\":\"checkmode-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant: $TENANT_ID"

# Create a config with the default mode (location_only)
cfg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 2,
      "minIntervalMinutes": 60,
      "allowedStartTime": "08:00:00",
      "allowedEndTime": "17:00:00",
      "checkMode": "location_only",
      "applicableRoles": ["employee"],
      "responseWindowSeconds": 300
    }')
if [ "$(echo "$cfg_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: config"; exit 1; fi
CONFIG_ID=$(echo "$cfg_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Config: $CONFIG_ID"
echo ""

MODE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID/check-mode"

# ── Create-time mode validation ───────────────────────────────────────────────
echo "--- Test 1: Config created with mode 'location_only' ---"
check_mode_value "Initial mode is location_only" "$CONFIG_ID" "location_only"

echo ""
echo "--- Test 2: Switch to location_face via dedicated endpoint ---"
upd_resp=$(curl -s -w "\n%{http_code}" \
    -X PUT "$MODE_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"checkMode": "location_face"}')
upd_status=$(echo "$upd_resp" | tail -n 1)
upd_body=$(echo "$upd_resp" | head -n -1)
if [ "$upd_status" -eq 200 ]; then
    echo "PASS: Switch to location_face (HTTP 200)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Switch to location_face — expected 200, got $upd_status"
    echo "Body: $upd_body"
    FAIL=$((FAIL + 1))
fi
check_mode_value "Mode persisted as location_face" "$CONFIG_ID" "location_face"

echo ""
echo "--- Test 3: Switch to location_face_liveness ---"
run_test "Switch to location_face_liveness returns 200" 200 \
    -X PUT "$MODE_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"checkMode": "location_face_liveness"}'
check_mode_value "Mode persisted as location_face_liveness" "$CONFIG_ID" "location_face_liveness"

echo ""
echo "--- Test 4: Switch back to location_only ---"
run_test "Switch back to location_only returns 200" 200 \
    -X PUT "$MODE_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"checkMode": "location_only"}'
check_mode_value "Mode persisted as location_only" "$CONFIG_ID" "location_only"

echo ""
echo "--- Test 5: Invalid mode value — should return 400 ---"
run_test "Invalid mode 'gps_only' returns 400" 400 \
    -X PUT "$MODE_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"checkMode": "gps_only"}'

echo ""
echo "--- Test 6: Empty mode value — should return 400 ---"
run_test "Empty checkMode returns 400" 400 \
    -X PUT "$MODE_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"checkMode": ""}'

echo ""
echo "--- Test 7: Missing checkMode field — should return 400 ---"
run_test "Missing checkMode field returns 400" 400 \
    -X PUT "$MODE_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{}'

echo ""
echo "--- Test 8: Unauthorized request — should return 401 ---"
run_test "No auth token returns 401" 401 \
    -X PUT "$MODE_URL" \
    -H "Content-Type: application/json" \
    -d '{"checkMode": "location_face"}'

echo ""
echo "--- Test 9: Unknown config ID — should return 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000000"
run_test "Unknown configId returns 404" 404 \
    -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$FAKE_ID/check-mode" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"checkMode": "location_only"}'

echo ""
echo "--- Test 10: Updating mode does not change other fields ---"
get_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
get_body=$(echo "$get_resp" | head -n -1)
checks=$(echo "$get_body" | grep -o '"checksPerShift":[0-9]*' | cut -d: -f2)
interval=$(echo "$get_body" | grep -o '"minIntervalMinutes":[0-9]*' | cut -d: -f2)
if [ "$checks" = "2" ] && [ "$interval" = "60" ]; then
    echo "PASS: Other scheduling fields unchanged after mode update"
    PASS=$((PASS + 1))
else
    echo "FAIL: Scheduling fields changed unexpectedly — checksPerShift=$checks minIntervalMinutes=$interval"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 11: Create config with location_face_liveness mode from the start ---"
t2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Face Mode Corp ${TS}2\",\"slug\":\"face-mode-${TS}\"}")
T2_ID=$(echo "$t2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
cfg2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$T2_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 1,
      "minIntervalMinutes": 0,
      "allowedStartTime": "08:00:00",
      "allowedEndTime": "17:00:00",
      "checkMode": "location_face_liveness",
      "applicableRoles": [],
      "responseWindowSeconds": 120
    }')
cfg2_status=$(echo "$cfg2_resp" | tail -n 1)
cfg2_body=$(echo "$cfg2_resp" | head -n -1)
cfg2_mode=$(echo "$cfg2_body" | grep -o '"checkMode":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ "$cfg2_status" -eq 201 ] && [ "$cfg2_mode" = "location_face_liveness" ]; then
    echo "PASS: Create with location_face_liveness returns 201 and mode is correct"
    PASS=$((PASS + 1))
else
    echo "FAIL: Create with location_face_liveness — HTTP $cfg2_status, checkMode='$cfg2_mode'"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
