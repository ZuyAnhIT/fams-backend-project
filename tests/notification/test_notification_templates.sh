#!/usr/bin/env bash
# Tests for notification template CRUD (Task 139)
# POST/GET/PUT/DELETE /api/v1/tenants/{tenantId}/notification-templates
# Usage: BASE_URL=http://localhost:8080 ACCESS_TOKEN=<token> bash test_notification_templates.sh
# If ACCESS_TOKEN is not provided, will attempt login as admin@fams.com

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

echo "=== Notification Template Tests (Task 139) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: Admin login ─────────────────────────────────────────────────────────
echo "--- Setup ---"

if [ -n "${ACCESS_TOKEN:-}" ]; then
    ADMIN_TOKEN="$ACCESS_TOKEN"
    echo "Using provided ACCESS_TOKEN"
else
    login_resp=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
    if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then
        echo "SETUP FAILED: admin login"
        exit 1
    fi
    ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "Logged in as admin@fams.com"
fi

TS=$(date +%s)

# Create a tenant for this test run
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"ownerEmail\":\"admin@fams.com\",\"name\":\"Template Corp ${TS}\",\"slug\":\"tmpl-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: create tenant (got $(echo "$t_resp" | tail -n 1))"
    echo "  Body: $(echo "$t_resp" | head -n -1)"
    exit 1
fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup complete."
echo "  TENANT_ID=$TENANT_ID"
echo ""

TMPL_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/notification-templates"

# ── Test 1: 401 without token ─────────────────────────────────────────────────
echo "--- Test 1: 401 without token ---"
run_test "POST without token returns 401" 401 \
    -X POST "$TMPL_URL" \
    -H "Content-Type: application/json" \
    -d '{"eventType":"TEST","titleTemplate":"T","bodyTemplate":"B"}'

# ── Test 2: POST creates template → 201 ──────────────────────────────────────
echo ""
echo "--- Test 2: POST creates template ---"
create_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$TMPL_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{
      \"eventType\": \"RANDOM_CHECK_DISPATCHED\",
      \"locale\": \"vi\",
      \"titleTemplate\": \"Kiểm tra ngẫu nhiên cho {studentName}\",
      \"bodyTemplate\": \"Kính gửi {studentName}, buổi kiểm tra ngẫu nhiên đã được giao.\"
    }")
create_status=$(echo "$create_resp" | tail -n 1)
create_body=$(echo "$create_resp" | head -n -1)

if [ "$create_status" -eq 201 ]; then
    echo "PASS: POST /notification-templates returns 201 (HTTP $create_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: POST /notification-templates returned $create_status"
    echo "  Body: $create_body"
    FAIL=$((FAIL + 1))
fi

TEMPLATE_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "  Created template id=$TEMPLATE_ID"

# ── Test 3: POST returns correct fields ──────────────────────────────────────
echo ""
echo "--- Test 3: POST response contains correct fields ---"
check_contains "Response has eventType" "$create_body" '"eventType":"RANDOM_CHECK_DISPATCHED"'
check_contains "Response has locale" "$create_body" '"locale":"vi"'
check_contains "Response has titleTemplate" "$create_body" '"titleTemplate"'
check_contains "Response has bodyTemplate" "$create_body" '"bodyTemplate"'
check_contains "Response has tenantId" "$create_body" "\"tenantId\":\"$TENANT_ID\""
check_contains "Response has createdAt" "$create_body" '"createdAt"'
check_contains "Response has updatedAt" "$create_body" '"updatedAt"'

# ── Test 4: GET / lists templates → 200 with items ───────────────────────────
echo ""
echo "--- Test 4: GET / lists templates ---"
list_resp=$(curl -s -w "\n%{http_code}" \
    "$TMPL_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_status=$(echo "$list_resp" | tail -n 1)
list_body=$(echo "$list_resp" | head -n -1)

if [ "$list_status" -eq 200 ]; then
    echo "PASS: GET /notification-templates returns 200 (HTTP $list_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: GET /notification-templates returned $list_status"
    echo "  Body: $list_body"
    FAIL=$((FAIL + 1))
fi
check_contains "List response contains content array" "$list_body" '"content"'
check_contains "List response contains template id" "$list_body" "$TEMPLATE_ID"

# ── Test 5: GET /{id} returns specific template → 200 ────────────────────────
echo ""
echo "--- Test 5: GET /{id} returns specific template ---"
get_resp=$(curl -s -w "\n%{http_code}" \
    "$TMPL_URL/$TEMPLATE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
get_status=$(echo "$get_resp" | tail -n 1)
get_body=$(echo "$get_resp" | head -n -1)

if [ "$get_status" -eq 200 ]; then
    echo "PASS: GET /{id} returns 200 (HTTP $get_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: GET /{id} returned $get_status"
    echo "  Body: $get_body"
    FAIL=$((FAIL + 1))
fi
check_contains "GET /{id} has correct id" "$get_body" "\"id\":\"$TEMPLATE_ID\""
check_contains "GET /{id} has eventType" "$get_body" '"eventType":"RANDOM_CHECK_DISPATCHED"'

# ── Test 6: PUT /{id} updates template → 200 with updated fields ──────────────
echo ""
echo "--- Test 6: PUT /{id} updates template ---"
put_resp=$(curl -s -w "\n%{http_code}" \
    -X PUT "$TMPL_URL/$TEMPLATE_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{
      \"titleTemplate\": \"Cập nhật: Kiểm tra ngẫu nhiên cho {studentName}\",
      \"bodyTemplate\": \"Cập nhật: Kính gửi {studentName}, buổi kiểm tra ngẫu nhiên đã được giao.\"
    }")
put_status=$(echo "$put_resp" | tail -n 1)
put_body=$(echo "$put_resp" | head -n -1)

if [ "$put_status" -eq 200 ]; then
    echo "PASS: PUT /{id} returns 200 (HTTP $put_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: PUT /{id} returned $put_status"
    echo "  Body: $put_body"
    FAIL=$((FAIL + 1))
fi
check_contains "PUT response has updated titleTemplate" "$put_body" "Cập nhật:"

# ── Test 7: Duplicate (same tenant+event_type+locale) → 409 ──────────────────
echo ""
echo "--- Test 7: Duplicate template returns 409 ---"
run_test "Duplicate template returns 409" 409 \
    -X POST "$TMPL_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{
      \"eventType\": \"RANDOM_CHECK_DISPATCHED\",
      \"locale\": \"vi\",
      \"titleTemplate\": \"Duplicate title\",
      \"bodyTemplate\": \"Duplicate body\"
    }"

# ── Test 8: POST validation error — missing required fields → 400 ─────────────
echo ""
echo "--- Test 8: Missing required fields returns 400 ---"
run_test "Missing eventType returns 400" 400 \
    -X POST "$TMPL_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{
      \"locale\": \"vi\",
      \"titleTemplate\": \"No event type\",
      \"bodyTemplate\": \"No event type body\"
    }"

# ── Test 9: GET /{nonexistent-id} returns 404 ────────────────────────────────
echo ""
echo "--- Test 9: GET non-existent template returns 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000001"
run_test "GET non-existent template returns 404" 404 \
    "$TMPL_URL/$FAKE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# ── Test 10: Create a second template with different locale ───────────────────
echo ""
echo "--- Test 10: Create template with different locale (en) ---"
create_en_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$TMPL_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{
      \"eventType\": \"RANDOM_CHECK_DISPATCHED\",
      \"locale\": \"en\",
      \"titleTemplate\": \"Random check for {studentName}\",
      \"bodyTemplate\": \"Dear {studentName}, a random check has been dispatched.\"
    }")
create_en_status=$(echo "$create_en_resp" | tail -n 1)
create_en_body=$(echo "$create_en_resp" | head -n -1)
TEMPLATE_EN_ID=$(echo "$create_en_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ "$create_en_status" -eq 201 ]; then
    echo "PASS: POST with locale=en returns 201 (HTTP $create_en_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: POST with locale=en returned $create_en_status"
    echo "  Body: $create_en_body"
    FAIL=$((FAIL + 1))
fi

# ── Test 11: List now has 2 templates ────────────────────────────────────────
echo ""
echo "--- Test 11: List contains both templates ---"
list_resp2=$(curl -s \
    "$TMPL_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
check_contains "List contains vi template" "$list_resp2" "$TEMPLATE_ID"
check_contains "List contains en template" "$list_resp2" "$TEMPLATE_EN_ID"

# ── Test 12: DELETE /{id} → 204 ──────────────────────────────────────────────
echo ""
echo "--- Test 12: DELETE /{id} returns 204 ---"
run_test "DELETE /{id} returns 204" 204 \
    -X DELETE "$TMPL_URL/$TEMPLATE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# ── Test 13: GET /{id} after delete → 404 ────────────────────────────────────
echo ""
echo "--- Test 13: GET /{id} after delete returns 404 ---"
run_test "GET deleted template returns 404" 404 \
    "$TMPL_URL/$TEMPLATE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# ── Test 14: DELETE /{nonexistent-id} → 404 ──────────────────────────────────
echo ""
echo "--- Test 14: DELETE non-existent template returns 404 ---"
run_test "DELETE non-existent template returns 404" 404 \
    -X DELETE "$TMPL_URL/$FAKE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# ── Test 15: GET / without token returns 401 ─────────────────────────────────
echo ""
echo "--- Test 15: GET / without token returns 401 ---"
run_test "GET / without token returns 401" 401 \
    "$TMPL_URL"

# ── Test 16: Default locale is 'vi' when not specified ───────────────────────
echo ""
echo "--- Test 16: Default locale is 'vi' when not specified ---"
default_locale_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$TMPL_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{
      \"eventType\": \"ATTENDANCE_ALERT\",
      \"titleTemplate\": \"Cảnh báo điểm danh {studentName}\",
      \"bodyTemplate\": \"Học sinh {studentName} có điểm danh bất thường.\"
    }")
default_locale_status=$(echo "$default_locale_resp" | tail -n 1)
default_locale_body=$(echo "$default_locale_resp" | head -n -1)

if [ "$default_locale_status" -eq 201 ]; then
    echo "PASS: POST without locale returns 201 (HTTP $default_locale_status)"
    PASS=$((PASS + 1))
    check_contains "Default locale is vi" "$default_locale_body" '"locale":"vi"'
else
    echo "FAIL: POST without locale returned $default_locale_status"
    echo "  Body: $default_locale_body"
    FAIL=$((FAIL + 1))
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
