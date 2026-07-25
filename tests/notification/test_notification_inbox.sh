#!/usr/bin/env bash
# Tests for notification inbox (Task 88)
# GET  /api/v1/tenants/{tenantId}/notifications
# POST /internal/notifications
# Usage: BASE_URL=http://localhost:8080 bash test_notification_inbox.sh

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
        FAIL=$((FAIL + 1))
    else
        echo "PASS: $name (does not contain '$needle')"
        PASS=$((PASS + 1))
    fi
}

# Extracts userId from /api/v1/auth/me given a token
get_user_id() {
    local token="$1"
    curl -s "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $token" \
        | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4
}

echo "=== Notification Inbox Tests (Task 88) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: Admin login ─────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then
    echo "SETUP FAILED: admin login"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

# Create a tenant
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Notif Corp ${TS}\",\"slug\":\"notif-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Invite employee 1 and accept
EMP1_EMAIL="notif.emp1.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP1_EMAIL\",\"firstName\":\"Notif\",\"lastName\":\"One\"}"

# Get invitation token from the admin list endpoint
inv_page=$(curl -s \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
INV_TOKEN1=$(echo "$inv_page" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)

accept_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN1\",\"password\":\"Employee@1234\"}")
accept_status=$(echo "$accept_resp" | tail -n 1)
if [ "$accept_status" -ne 200 ] && [ "$accept_status" -ne 201 ]; then
    echo "SETUP FAILED: emp1 invitation accept returned $accept_status"
    exit 1
fi

emp1_login=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMP1_EMAIL\",\"password\":\"Employee@1234\"}")
if [ "$(echo "$emp1_login" | tail -n 1)" -ne 200 ]; then
    echo "SETUP FAILED: emp1 login"
    exit 1
fi
EMP1_TOKEN=$(echo "$emp1_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
EMP1_USER_ID=$(get_user_id "$EMP1_TOKEN")

# Invite employee 2 (for isolation test)
EMP2_EMAIL="notif.emp2.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP2_EMAIL\",\"firstName\":\"Notif\",\"lastName\":\"Two\"}"

inv_page2=$(curl -s \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
# Get the token for emp2 (last pending token in the list, or filter by email)
INV_TOKEN2=$(echo "$inv_page2" \
    | grep -o '"email":"[^"]*","status":"pending"[^}]*"token":"[^"]*"' \
    | grep "$EMP2_EMAIL" \
    | grep -o '"token":"[^"]*"' | cut -d'"' -f4 || true)
if [ -z "$INV_TOKEN2" ]; then
    # Fallback: get all tokens and pick the second one
    INV_TOKEN2=$(echo "$inv_page2" | grep -o '"token":"[^"]*"' | tail -1 | cut -d'"' -f4)
fi

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN2\",\"password\":\"Employee@1234\"}" 2>/dev/null || true

emp2_login=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMP2_EMAIL\",\"password\":\"Employee@1234\"}")
EMP2_LOGIN_STATUS=$(echo "$emp2_login" | tail -n 1)
EMP2_TOKEN=""
EMP2_USER_ID=""
if [ "$EMP2_LOGIN_STATUS" -eq 200 ]; then
    EMP2_TOKEN=$(echo "$emp2_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
    EMP2_USER_ID=$(get_user_id "$EMP2_TOKEN")
fi

echo "Setup complete."
echo "  TENANT_ID=$TENANT_ID"
echo "  EMP1_USER_ID=$EMP1_USER_ID"
echo "  EMP2_USER_ID=$EMP2_USER_ID"
echo ""

NOTIF_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/notifications"
INTERNAL_URL="$BASE_URL/internal/notifications"

# ── Test 1: 401 without token ──────────────────────────────────────────────────
echo "--- Test 1: 401 without token ---"
run_test "GET notifications without token returns 401" 401 \
    "$NOTIF_URL"

# ── Test 2: GET returns 200 with expected structure ────────────────────────────
echo ""
echo "--- Test 2: GET returns 200 ---"
get_resp=$(curl -s -w "\n%{http_code}" \
    "$NOTIF_URL" \
    -H "Authorization: Bearer $EMP1_TOKEN")
get_status=$(echo "$get_resp" | tail -n 1)
get_body=$(echo "$get_resp" | head -n -1)
if [ "$get_status" -eq 200 ]; then
    echo "PASS: GET /notifications returns 200 (HTTP $get_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: GET /notifications returned $get_status"
    echo "  Body: $get_body"
    FAIL=$((FAIL + 1))
fi

# ── Test 3: unreadCount present in response ────────────────────────────────────
echo ""
echo "--- Test 3: unreadCount field present ---"
check_contains "Response contains unreadCount" "$get_body" "unreadCount"

# ── Test 4: items array present ───────────────────────────────────────────────
echo ""
echo "--- Test 4: items array present ---"
check_contains "Response contains items" "$get_body" '"items"'

# ── Test 5: POST internal notification creates one ─────────────────────────────
echo ""
echo "--- Test 5: POST /internal/notifications creates notification ---"
create_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$INTERNAL_URL" \
    -H "Content-Type: application/json" \
    -d "{
      \"tenantId\": \"$TENANT_ID\",
      \"userId\": \"$EMP1_USER_ID\",
      \"eventType\": \"TEST_EVENT\",
      \"title\": \"Test Notification\",
      \"body\": \"This is a test notification body\"
    }")
create_status=$(echo "$create_resp" | tail -n 1)
create_body=$(echo "$create_resp" | head -n -1)
if [ "$create_status" -eq 201 ]; then
    echo "PASS: POST /internal/notifications returns 201 (HTTP $create_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: POST /internal/notifications returned $create_status"
    echo "  Body: $create_body"
    FAIL=$((FAIL + 1))
fi
NOTIF_ID=$(echo "$create_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "  Created notification id=$NOTIF_ID"

# ── Test 6: After creating, GET shows new notification ────────────────────────
echo ""
echo "--- Test 6: GET shows newly created notification ---"
get_resp2=$(curl -s -w "\n%{http_code}" \
    "$NOTIF_URL" \
    -H "Authorization: Bearer $EMP1_TOKEN")
get_body2=$(echo "$get_resp2" | head -n -1)
check_contains "Inbox contains new notification id" "$get_body2" "$NOTIF_ID"

# ── Test 7: unreadCount >= 1 after creating unread notification ────────────────
echo ""
echo "--- Test 7: unreadCount >= 1 after new notification ---"
unread_count=$(echo "$get_body2" | grep -o '"unreadCount":[0-9]*' | cut -d':' -f2)
if [ -n "$unread_count" ] && [ "$unread_count" -ge 1 ]; then
    echo "PASS: unreadCount=$unread_count (>=1)"
    PASS=$((PASS + 1))
else
    echo "FAIL: unreadCount='$unread_count' (expected >=1)"
    FAIL=$((FAIL + 1))
fi

# ── Test 8: ?unreadOnly=true returns only unread notifications ─────────────────
echo ""
echo "--- Test 8: ?unreadOnly=true returns only unread ---"
unread_resp=$(curl -s -w "\n%{http_code}" \
    "$NOTIF_URL?unreadOnly=true" \
    -H "Authorization: Bearer $EMP1_TOKEN")
unread_status=$(echo "$unread_resp" | tail -n 1)
unread_body=$(echo "$unread_resp" | head -n -1)
if [ "$unread_status" -eq 200 ]; then
    echo "PASS: GET ?unreadOnly=true returns 200 (HTTP $unread_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: GET ?unreadOnly=true returned $unread_status"
    FAIL=$((FAIL + 1))
fi
check_contains "unreadOnly response contains notification" "$unread_body" "$NOTIF_ID"

# ── Test 9: Isolation — emp2 cannot see emp1's notifications ──────────────────
echo ""
echo "--- Test 9: Isolation — emp2 cannot see emp1's notifications ---"
if [ -n "$EMP2_TOKEN" ]; then
    emp2_resp=$(curl -s \
        "$NOTIF_URL" \
        -H "Authorization: Bearer $EMP2_TOKEN")
    check_not_contains "emp2 cannot see emp1's notification" "$emp2_resp" "$NOTIF_ID"
else
    echo "SKIP: emp2 token not available, skipping isolation test"
fi

# ── Test 10: Pagination params accepted ───────────────────────────────────────
echo ""
echo "--- Test 10: Pagination params accepted ---"
run_test "Pagination with page=0&size=5 returns 200" 200 \
    "$NOTIF_URL?page=0&size=5" \
    -H "Authorization: Bearer $EMP1_TOKEN"

# ── Test 11: totalPages and totalElements present ─────────────────────────────
echo ""
echo "--- Test 11: totalPages and totalElements in response ---"
check_contains "Response contains totalPages" "$get_body2" "totalPages"
check_contains "Response contains totalElements" "$get_body2" "totalElements"

# ── Test 12: POST internal — validation error missing required fields ──────────
echo ""
echo "--- Test 12: POST /internal/notifications missing tenantId returns 400 ---"
run_test "Missing tenantId returns 400" 400 \
    -X POST "$INTERNAL_URL" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"$EMP1_USER_ID\",\"eventType\":\"T\",\"title\":\"T\"}"

# ── Test 13: Second notification increases unreadCount ───────────────────────
echo ""
echo "--- Test 13: Second notification increases unreadCount ---"
curl -s -o /dev/null -X POST "$INTERNAL_URL" \
    -H "Content-Type: application/json" \
    -d "{
      \"tenantId\": \"$TENANT_ID\",
      \"userId\": \"$EMP1_USER_ID\",
      \"eventType\": \"TEST_EVENT_2\",
      \"title\": \"Second Notification\",
      \"body\": \"Body of second notification\"
    }"
get_resp3=$(curl -s "$NOTIF_URL" -H "Authorization: Bearer $EMP1_TOKEN")
unread_count3=$(echo "$get_resp3" | grep -o '"unreadCount":[0-9]*' | cut -d':' -f2)
if [ -n "$unread_count3" ] && [ "$unread_count3" -ge 2 ]; then
    echo "PASS: unreadCount=$unread_count3 (>=2 after two notifications)"
    PASS=$((PASS + 1))
else
    echo "FAIL: unreadCount='$unread_count3' after second notification (expected >=2)"
    FAIL=$((FAIL + 1))
fi

# ── Test 14: isRead=false on new notification ─────────────────────────────────
echo ""
echo "--- Test 14: New notification has isRead=false ---"
check_contains "Notification has isRead:false" "$get_body2" '"isRead":false'

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
