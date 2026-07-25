#!/usr/bin/env bash
# Tests for user notification settings (Task 141)
# GET /api/v1/me/notification-settings
# PUT /api/v1/me/notification-settings/{eventType}
# PUT /api/v1/me/notification-settings (bulk)
# Usage: BASE_URL=http://localhost:8080 ACCESS_TOKEN=<jwt> bash test_notification_settings.sh
# If ACCESS_TOKEN is not set, the script will attempt admin login automatically.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

# ── Helpers ───────────────────────────────────────────────────────────────────

run_test() {
    local name="$1" expected_status="$2"
    local actual_status
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${@:3}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

check_val() {
    local name="$1" actual="$2" expected="$3"
    if [ "$actual" = "$expected" ]; then
        echo "PASS: $name (=$expected)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected '$expected', got '$actual'"
        FAIL=$((FAIL + 1))
    fi
}

check_contains() {
    local name="$1" haystack="$2" needle="$3"
    if echo "$haystack" | grep -q "$needle"; then
        echo "PASS: $name (contains '$needle')"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — response did not contain '$needle'"
        echo "  Body: $haystack"
        FAIL=$((FAIL + 1))
    fi
}

check_not_contains() {
    local name="$1" haystack="$2" needle="$3"
    if echo "$haystack" | grep -q "$needle"; then
        echo "FAIL: $name — response should NOT contain '$needle'"
        echo "  Body: $haystack"
        FAIL=$((FAIL + 1))
    else
        echo "PASS: $name (does not contain '$needle')"
        PASS=$((PASS + 1))
    fi
}

echo "=== Notification Settings Tests (Task 141) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: Login ──────────────────────────────────────────────────────────────
echo "--- Setup ---"

if [ -z "${ACCESS_TOKEN:-}" ]; then
    login_resp=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
    login_status=$(echo "$login_resp" | tail -n 1)
    login_body=$(echo "$login_resp" | head -n -1)
    if [ "$login_status" -ne 200 ]; then
        echo "SETUP FAILED: admin login returned HTTP $login_status"
        exit 1
    fi
    ACCESS_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
    if [ -z "$ACCESS_TOKEN" ]; then
        echo "SETUP FAILED: could not extract accessToken from login response"
        exit 1
    fi
    echo "Logged in as admin, token acquired."
else
    echo "Using provided ACCESS_TOKEN."
fi

# Get userId from /auth/me
user_resp=$(curl -s "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $ACCESS_TOKEN")
USER_ID=$(echo "$user_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$USER_ID" ]; then
    echo "SETUP FAILED: could not extract userId from /auth/me"
    exit 1
fi

# Get a tenant ID for internal notification creation
tenant_resp=$(curl -s "$BASE_URL/api/v1/tenants" -H "Authorization: Bearer $ACCESS_TOKEN")
TENANT_ID=$(echo "$tenant_resp" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$TENANT_ID" ]; then
    # Fallback: use a dummy UUID; internal create endpoint may not need a real tenant
    TENANT_ID="00000000-0000-0000-0000-000000000001"
fi

echo "userId=$USER_ID"
echo "tenantId=$TENANT_ID"
echo ""

# ── Test 1: GET /me/notification-settings → 200 (empty or existing) ───────────
echo "--- Test 1: GET settings (empty or existing) ---"
get_resp=$(curl -s -w "\n%{http_code}" \
    "$BASE_URL/api/v1/me/notification-settings" \
    -H "Authorization: Bearer $ACCESS_TOKEN")
get_status=$(echo "$get_resp" | tail -n 1)
get_body=$(echo "$get_resp" | head -n -1)
run_test "GET /me/notification-settings returns 200" 200 \
    "$BASE_URL/api/v1/me/notification-settings" \
    -H "Authorization: Bearer $ACCESS_TOKEN"
check_contains "GET response has success=true" "$get_body" '"success":true'
echo ""

# ── Test 2: PUT /{eventType} → 200, correct fields returned ──────────────────
echo "--- Test 2: PUT single setting ---"
put_resp=$(curl -s -w "\n%{http_code}" \
    -X PUT "$BASE_URL/api/v1/me/notification-settings/TEST_EVENT" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"inAppEnabled":true,"pushEnabled":false}')
put_status=$(echo "$put_resp" | tail -n 1)
put_body=$(echo "$put_resp" | head -n -1)

run_test "PUT /me/notification-settings/TEST_EVENT returns 200" 200 \
    -X PUT "$BASE_URL/api/v1/me/notification-settings/TEST_EVENT" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"inAppEnabled":true,"pushEnabled":false}'
check_contains "PUT response has eventType=TEST_EVENT" "$put_body" '"eventType":"TEST_EVENT"'
check_contains "PUT response has inAppEnabled=true" "$put_body" '"inAppEnabled":true'
check_contains "PUT response has pushEnabled=false" "$put_body" '"pushEnabled":false'
check_contains "PUT response has userId" "$put_body" '"userId":"'
check_contains "PUT response has id" "$put_body" '"id":"'
echo ""

# ── Test 3: GET after PUT shows the setting ───────────────────────────────────
echo "--- Test 3: GET after PUT shows the setting ---"
get2_resp=$(curl -s \
    "$BASE_URL/api/v1/me/notification-settings" \
    -H "Authorization: Bearer $ACCESS_TOKEN")
check_contains "GET after PUT contains TEST_EVENT" "$get2_resp" '"eventType":"TEST_EVENT"'
check_contains "GET after PUT shows pushEnabled=false" "$get2_resp" '"pushEnabled":false'
echo ""

# ── Test 4: Disable in-app → notification NOT created ────────────────────────
echo "--- Test 4: Disable in-app → notification skipped ---"
# Get initial count of notifications in inbox
count_before=$(curl -s \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/notifications" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    | grep -o '"totalElements":[0-9]*' | cut -d':' -f2 || echo "0")
echo "Notification count before: $count_before"

# Disable in-app for SETTING_TEST_DISABLED
curl -s -X PUT "$BASE_URL/api/v1/me/notification-settings/SETTING_TEST_DISABLED" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"inAppEnabled":false,"pushEnabled":false}' > /dev/null

# Attempt to create notification with that event type
create_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/internal/notifications" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"userId\":\"$USER_ID\",\"eventType\":\"SETTING_TEST_DISABLED\",\"title\":\"Should not appear\",\"body\":\"disabled\"}")
create_status=$(echo "$create_resp" | tail -n 1)
create_body=$(echo "$create_resp" | head -n -1)

run_test "POST /internal/notifications returns 201 even when skipped" 201 \
    -X POST "$BASE_URL/internal/notifications" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"userId\":\"$USER_ID\",\"eventType\":\"SETTING_TEST_DISABLED\",\"title\":\"Should not appear\",\"body\":\"disabled\"}"

# Response data should be null since notification was not created
check_contains "Response data is null when skipped" "$create_body" '"data":null'

# Count after — should be same
count_after=$(curl -s \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/notifications" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    | grep -o '"totalElements":[0-9]*' | cut -d':' -f2 || echo "0")
echo "Notification count after (disabled): $count_after"
check_val "Notification count unchanged when in-app disabled" "$count_after" "$count_before"
echo ""

# ── Test 5: Enable in-app → notification IS created ──────────────────────────
echo "--- Test 5: Enable in-app → notification created ---"
# Re-enable in-app for SETTING_TEST_ENABLED
curl -s -X PUT "$BASE_URL/api/v1/me/notification-settings/SETTING_TEST_ENABLED" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"inAppEnabled":true,"pushEnabled":true}' > /dev/null

count_before2=$(curl -s \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/notifications" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    | grep -o '"totalElements":[0-9]*' | cut -d':' -f2 || echo "0")
echo "Notification count before enabled test: $count_before2"

curl -s -X POST "$BASE_URL/internal/notifications" \
    -H "Content-Type: application/json" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"userId\":\"$USER_ID\",\"eventType\":\"SETTING_TEST_ENABLED\",\"title\":\"Should appear\",\"body\":\"enabled\"}" > /dev/null

count_after2=$(curl -s \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/notifications" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    | grep -o '"totalElements":[0-9]*' | cut -d':' -f2 || echo "0")
echo "Notification count after enabled test: $count_after2"

expected_count=$((count_before2 + 1))
check_val "Notification count increased when in-app enabled" "$count_after2" "$expected_count"
echo ""

# ── Test 6: Bulk PUT with multiple settings → 200 ────────────────────────────
echo "--- Test 6: Bulk PUT ---"
bulk_resp=$(curl -s -w "\n%{http_code}" \
    -X PUT "$BASE_URL/api/v1/me/notification-settings" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"settings":[{"eventType":"BULK_EVENT_A","inAppEnabled":true,"pushEnabled":false},{"eventType":"BULK_EVENT_B","inAppEnabled":false,"pushEnabled":true},{"eventType":"BULK_EVENT_C","inAppEnabled":true,"pushEnabled":true}]}')
bulk_status=$(echo "$bulk_resp" | tail -n 1)
bulk_body=$(echo "$bulk_resp" | head -n -1)

run_test "Bulk PUT /me/notification-settings returns 200" 200 \
    -X PUT "$BASE_URL/api/v1/me/notification-settings" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"settings":[{"eventType":"BULK_EVENT_A","inAppEnabled":true,"pushEnabled":false},{"eventType":"BULK_EVENT_B","inAppEnabled":false,"pushEnabled":true},{"eventType":"BULK_EVENT_C","inAppEnabled":true,"pushEnabled":true}]}'

check_contains "Bulk response has BULK_EVENT_A" "$bulk_body" '"eventType":"BULK_EVENT_A"'
check_contains "Bulk response has BULK_EVENT_B" "$bulk_body" '"eventType":"BULK_EVENT_B"'
check_contains "Bulk response has BULK_EVENT_C" "$bulk_body" '"eventType":"BULK_EVENT_C"'
echo ""

# ── Test 7: GET shows all bulk settings ──────────────────────────────────────
echo "--- Test 7: GET shows all bulk settings ---"
get3_resp=$(curl -s \
    "$BASE_URL/api/v1/me/notification-settings" \
    -H "Authorization: Bearer $ACCESS_TOKEN")
check_contains "GET shows BULK_EVENT_A" "$get3_resp" '"eventType":"BULK_EVENT_A"'
check_contains "GET shows BULK_EVENT_B" "$get3_resp" '"eventType":"BULK_EVENT_B"'
check_contains "GET shows BULK_EVENT_C" "$get3_resp" '"eventType":"BULK_EVENT_C"'
echo ""

# ── Test 8: 401 without token ─────────────────────────────────────────────────
echo "--- Test 8: 401 without token ---"
run_test "GET /me/notification-settings without token returns 401" 401 \
    "$BASE_URL/api/v1/me/notification-settings"
run_test "PUT /me/notification-settings/TEST_EVENT without token returns 401" 401 \
    -X PUT "$BASE_URL/api/v1/me/notification-settings/TEST_EVENT" \
    -H "Content-Type: application/json" \
    -d '{"inAppEnabled":true}'
run_test "Bulk PUT /me/notification-settings without token returns 401" 401 \
    -X PUT "$BASE_URL/api/v1/me/notification-settings" \
    -H "Content-Type: application/json" \
    -d '{"settings":[{"eventType":"X","inAppEnabled":true}]}'
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "========================================="
echo "Results: $PASS passed, $FAIL failed"
if [ "$FAIL" -eq 0 ]; then
    echo "ALL TESTS PASSED"
else
    echo "SOME TESTS FAILED"
    exit 1
fi
