#!/usr/bin/env bash
# Tests for scheduling-field validation: checks_per_shift, min_interval_minutes,
# allowed_start_time, allowed_end_time (task 93)
# Usage: BASE_URL=http://localhost:8080 bash test_scheduling_fields.sh

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

echo "=== Scheduling Fields Validation Tests ==="
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
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: login (HTTP $login_status)"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Token obtained."

TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Scheduling Test ${TS}\",\"slug\":\"sched-test-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant: $TENANT_ID"
echo ""

BASE_ENDPOINT="$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default"
AUTH="-H Authorization: Bearer $ADMIN_TOKEN"

# ── Time window validation ────────────────────────────────────────────────────
echo "--- Test 1: end_time equals start_time — should return 400 ---"
run_test "end_time == start_time returns 400" 400 \
    -X POST "$BASE_ENDPOINT" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 1,
      "minIntervalMinutes": 60,
      "allowedStartTime": "08:00:00",
      "allowedEndTime": "08:00:00",
      "checkMode": "location_only",
      "applicableRoles": ["employee"],
      "responseWindowSeconds": 120
    }'

echo ""
echo "--- Test 2: end_time before start_time — should return 400 ---"
run_test "end_time < start_time returns 400" 400 \
    -X POST "$BASE_ENDPOINT" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 1,
      "minIntervalMinutes": 0,
      "allowedStartTime": "17:00:00",
      "allowedEndTime": "08:00:00",
      "checkMode": "location_only",
      "applicableRoles": [],
      "responseWindowSeconds": 120
    }'

echo ""
echo "--- Test 3: window too short for checks_per_shift + interval — should return 400 ---"
# 3 checks, 120 min interval → needs at least 240 min, but window is only 60 min (08:00–09:00)
run_test "Impossible schedule (too few minutes for checks) returns 400" 400 \
    -X POST "$BASE_ENDPOINT" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 3,
      "minIntervalMinutes": 120,
      "allowedStartTime": "08:00:00",
      "allowedEndTime": "09:00:00",
      "checkMode": "location_only",
      "applicableRoles": [],
      "responseWindowSeconds": 120
    }'

echo ""
echo "--- Test 4: checks_per_shift below minimum (0) — should return 400 ---"
run_test "checksPerShift=0 returns 400" 400 \
    -X POST "$BASE_ENDPOINT" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 0,
      "minIntervalMinutes": 60,
      "allowedStartTime": "08:00:00",
      "allowedEndTime": "17:00:00",
      "checkMode": "location_only",
      "applicableRoles": [],
      "responseWindowSeconds": 120
    }'

echo ""
echo "--- Test 5: checks_per_shift above maximum (11) — should return 400 ---"
run_test "checksPerShift=11 returns 400" 400 \
    -X POST "$BASE_ENDPOINT" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 11,
      "minIntervalMinutes": 30,
      "allowedStartTime": "08:00:00",
      "allowedEndTime": "17:00:00",
      "checkMode": "location_only",
      "applicableRoles": [],
      "responseWindowSeconds": 120
    }'

echo ""
echo "--- Test 6: negative min_interval_minutes — should return 400 ---"
run_test "Negative minIntervalMinutes returns 400" 400 \
    -X POST "$BASE_ENDPOINT" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 2,
      "minIntervalMinutes": -1,
      "allowedStartTime": "08:00:00",
      "allowedEndTime": "17:00:00",
      "checkMode": "location_only",
      "applicableRoles": [],
      "responseWindowSeconds": 120
    }'

echo ""
echo "--- Test 7: valid scheduling fields — should return 201 ---"
create_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_ENDPOINT" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 3,
      "minIntervalMinutes": 90,
      "allowedStartTime": "07:00:00",
      "allowedEndTime": "16:00:00",
      "checkMode": "location_only",
      "applicableRoles": ["employee"],
      "responseWindowSeconds": 300
    }')
# 3 checks, 90 min interval → needs 180 min; window is 540 min → valid
create_status=$(echo "$create_resp" | tail -n 1)
create_body=$(echo "$create_resp" | head -n -1)
if [ "$create_status" -eq 201 ]; then
    echo "PASS: Valid scheduling config returns 201 (HTTP 201)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Valid scheduling config — expected 201, got $create_status"
    echo "Body: $create_body"
    FAIL=$((FAIL + 1))
fi
CONFIG_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo ""
echo "--- Test 8: update with end_time before start_time — should return 400 ---"
run_test "Update: end_time < start_time returns 400" 400 \
    -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"allowedStartTime": "15:00:00", "allowedEndTime": "09:00:00"}'

echo ""
echo "--- Test 9: update making window too short — should return 400 ---"
# existing window 07:00-16:00 (540 min), shrinking to 07:00-08:00 (60 min), 3 checks × 90 min = 180 min needed
run_test "Update: window too short for checks returns 400" 400 \
    -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"allowedEndTime": "08:00:00"}'

echo ""
echo "--- Test 10: valid partial update of scheduling fields — should return 200 ---"
run_test "Valid partial scheduling update returns 200" 200 \
    -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"checksPerShift": 2, "minIntervalMinutes": 60}'

echo ""
echo "--- Test 11: verify updated values persist — should return 200 with correct data ---"
get_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/$CONFIG_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
get_status=$(echo "$get_resp" | tail -n 1)
get_body=$(echo "$get_resp" | head -n -1)
if [ "$get_status" -eq 200 ]; then
    checks=$(echo "$get_body" | grep -o '"checksPerShift":[0-9]*' | cut -d: -f2)
    interval=$(echo "$get_body" | grep -o '"minIntervalMinutes":[0-9]*' | cut -d: -f2)
    if [ "$checks" = "2" ] && [ "$interval" = "60" ]; then
        echo "PASS: Updated scheduling values persisted correctly"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Unexpected values — checksPerShift=$checks minIntervalMinutes=$interval"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Get config returned HTTP $get_status"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
