#!/usr/bin/env bash
# Tests for mark-as-read endpoints (Task 90)
# PATCH /api/v1/tenants/{tenantId}/notifications/{notificationId}/read
# PATCH /api/v1/tenants/{tenantId}/notifications/read-all
# Usage: BASE_URL=http://localhost:8080 ACCESS_TOKEN=<token> bash test_mark_read.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
NOTIFICATIONS_INTERNAL_SECRET="${NOTIFICATIONS_INTERNAL_SECRET:-fams_notifications_secret_local_dev}"
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

get_user_id() {
    local token="$1"
    curl -s "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $token" \
        | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4
}

echo "=== Mark-as-Read Tests (Task 90) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
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

# Create tenant
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"MarkRead Corp ${TS}\",\"slug\":\"markread-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Invite + setup employee 1
EMP1_EMAIL="markread.emp1.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP1_EMAIL\",\"firstName\":\"Mark\",\"lastName\":\"One\"}"

inv_page=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
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

# Invite + setup employee 2 (for isolation tests)
EMP2_EMAIL="markread.emp2.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP2_EMAIL\",\"firstName\":\"Mark\",\"lastName\":\"Two\"}"

inv_page2=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
INV_TOKEN2=$(echo "$inv_page2" \
    | grep -o '"email":"[^"]*","status":"pending"[^}]*"token":"[^"]*"' \
    | grep "$EMP2_EMAIL" \
    | grep -o '"token":"[^"]*"' | cut -d'"' -f4 || true)
if [ -z "$INV_TOKEN2" ]; then
    INV_TOKEN2=$(echo "$inv_page2" | grep -o '"token":"[^"]*"' | tail -1 | cut -d'"' -f4)
fi

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN2\",\"password\":\"Employee@1234\"}" 2>/dev/null || true

emp2_login=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMP2_EMAIL\",\"password\":\"Employee@1234\"}")
EMP2_TOKEN=""
EMP2_USER_ID=""
if [ "$(echo "$emp2_login" | tail -n 1)" -eq 200 ]; then
    EMP2_TOKEN=$(echo "$emp2_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
    EMP2_USER_ID=$(get_user_id "$EMP2_TOKEN")
fi

# Create notifications for emp1
INTERNAL_URL="$BASE_URL/internal/notifications"
NOTIF_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/notifications"

notif1_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$INTERNAL_URL" \
    -H "Content-Type: application/json" \
    -H "X-Internal-Secret: $NOTIFICATIONS_INTERNAL_SECRET" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"userId\":\"$EMP1_USER_ID\",\"eventType\":\"TEST_READ\",\"title\":\"Notification One\",\"body\":\"Body one\"}")
if [ "$(echo "$notif1_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: create notif1"; exit 1; fi
NOTIF1_ID=$(echo "$notif1_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

notif2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$INTERNAL_URL" \
    -H "Content-Type: application/json" \
    -H "X-Internal-Secret: $NOTIFICATIONS_INTERNAL_SECRET" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"userId\":\"$EMP1_USER_ID\",\"eventType\":\"TEST_READ_2\",\"title\":\"Notification Two\",\"body\":\"Body two\"}")
if [ "$(echo "$notif2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: create notif2"; exit 1; fi
NOTIF2_ID=$(echo "$notif2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create a notification for emp2 (for isolation test)
NOTIF_EMP2_ID=""
if [ -n "$EMP2_USER_ID" ]; then
    notif_emp2_resp=$(curl -s -w "\n%{http_code}" \
        -X POST "$INTERNAL_URL" \
        -H "Content-Type: application/json" \
    -H "X-Internal-Secret: $NOTIFICATIONS_INTERNAL_SECRET" \
        -d "{\"tenantId\":\"$TENANT_ID\",\"userId\":\"$EMP2_USER_ID\",\"eventType\":\"TEST_READ_EMP2\",\"title\":\"Emp2 Notification\",\"body\":\"For emp2\"}")
    if [ "$(echo "$notif_emp2_resp" | tail -n 1)" -eq 201 ]; then
        NOTIF_EMP2_ID=$(echo "$notif_emp2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    fi
fi

echo "Setup complete."
echo "  TENANT_ID=$TENANT_ID"
echo "  EMP1_USER_ID=$EMP1_USER_ID"
echo "  EMP2_USER_ID=$EMP2_USER_ID"
echo "  NOTIF1_ID=$NOTIF1_ID"
echo "  NOTIF2_ID=$NOTIF2_ID"
echo "  NOTIF_EMP2_ID=$NOTIF_EMP2_ID"
echo ""

# ── Test 1: 401 without token on PATCH /{id}/read ─────────────────────────────
echo "--- Test 1: 401 without token on PATCH /{id}/read ---"
run_test "PATCH /{id}/read without token returns 401" 401 \
    -X PATCH "$NOTIF_URL/$NOTIF1_ID/read"

# ── Test 2: 401 without token on PATCH /read-all ──────────────────────────────
echo ""
echo "--- Test 2: 401 without token on PATCH /read-all ---"
run_test "PATCH /read-all without token returns 401" 401 \
    -X PATCH "$NOTIF_URL/read-all"

# ── Test 3: PATCH /{id}/read → 200, isRead=true ───────────────────────────────
echo ""
echo "--- Test 3: PATCH /{id}/read returns 200 with isRead=true ---"
read_resp=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$NOTIF_URL/$NOTIF1_ID/read" \
    -H "Authorization: Bearer $EMP1_TOKEN")
read_status=$(echo "$read_resp" | tail -n 1)
read_body=$(echo "$read_resp" | head -n -1)
if [ "$read_status" -eq 200 ]; then
    echo "PASS: PATCH /{id}/read returns 200 (HTTP $read_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: PATCH /{id}/read returned $read_status"
    echo "  Body: $read_body"
    FAIL=$((FAIL + 1))
fi
check_contains "Response has isRead:true" "$read_body" '"isRead":true'

# ── Test 4: read_at is non-null after marking as read ─────────────────────────
echo ""
echo "--- Test 4: read_at is non-null after marking as read ---"
check_not_contains "readAt is not null" "$read_body" '"readAt":null'
check_contains "readAt field present in response" "$read_body" '"readAt":'

# ── Test 5: PATCH /{id}/read for non-existent ID → 404 ───────────────────────
echo ""
echo "--- Test 5: PATCH /{id}/read for non-existent ID returns 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000000"
run_test "Non-existent notification returns 404" 404 \
    -X PATCH "$NOTIF_URL/$FAKE_ID/read" \
    -H "Authorization: Bearer $EMP1_TOKEN"

# ── Test 6: PATCH /{id}/read for another user's notification → 404 ────────────
echo ""
echo "--- Test 6: PATCH /{id}/read for another user's notification returns 404 ---"
if [ -n "$EMP2_TOKEN" ] && [ -n "$NOTIF_EMP2_ID" ]; then
    run_test "Another user's notification returns 404 (not 403)" 404 \
        -X PATCH "$NOTIF_URL/$NOTIF_EMP2_ID/read" \
        -H "Authorization: Bearer $EMP1_TOKEN"
else
    echo "SKIP: emp2 token or notification not available"
fi

# ── Test 7: PATCH /read-all → 200, markedCount > 0 ───────────────────────────
echo ""
echo "--- Test 7: PATCH /read-all returns 200 with markedCount > 0 ---"
readall_resp=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$NOTIF_URL/read-all" \
    -H "Authorization: Bearer $EMP1_TOKEN")
readall_status=$(echo "$readall_resp" | tail -n 1)
readall_body=$(echo "$readall_resp" | head -n -1)
if [ "$readall_status" -eq 200 ]; then
    echo "PASS: PATCH /read-all returns 200 (HTTP $readall_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: PATCH /read-all returned $readall_status"
    echo "  Body: $readall_body"
    FAIL=$((FAIL + 1))
fi

marked_count=$(echo "$readall_body" | grep -o '"markedCount":[0-9]*' | cut -d':' -f2)
if [ -n "$marked_count" ] && [ "$marked_count" -ge 1 ]; then
    echo "PASS: markedCount=$marked_count (>=1)"
    PASS=$((PASS + 1))
else
    echo "FAIL: markedCount='$marked_count' — expected >=1"
    echo "  Body: $readall_body"
    FAIL=$((FAIL + 1))
fi

# ── Test 8: After read-all, GET notifications shows unreadCount=0 ─────────────
echo ""
echo "--- Test 8: After read-all, unreadCount=0 ---"
get_after=$(curl -s "$NOTIF_URL" -H "Authorization: Bearer $EMP1_TOKEN")
unread_after=$(echo "$get_after" | grep -o '"unreadCount":[0-9]*' | cut -d':' -f2)
check_val "unreadCount=0 after read-all" "$unread_after" "0"

# ── Test 9: PATCH /read-all again → 200, markedCount=0 (idempotent) ──────────
echo ""
echo "--- Test 9: PATCH /read-all again returns markedCount=0 (idempotent) ---"
readall_resp2=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$NOTIF_URL/read-all" \
    -H "Authorization: Bearer $EMP1_TOKEN")
readall_status2=$(echo "$readall_resp2" | tail -n 1)
readall_body2=$(echo "$readall_resp2" | head -n -1)
if [ "$readall_status2" -eq 200 ]; then
    echo "PASS: Second PATCH /read-all returns 200 (HTTP $readall_status2)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Second PATCH /read-all returned $readall_status2"
    FAIL=$((FAIL + 1))
fi
marked_count2=$(echo "$readall_body2" | grep -o '"markedCount":[0-9]*' | cut -d':' -f2)
check_val "markedCount=0 when nothing to mark" "$marked_count2" "0"

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
