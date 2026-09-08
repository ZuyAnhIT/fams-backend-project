#!/usr/bin/env bash
# Tests for FCM retry and fallback notification (Task 140)
# Verifies:
#   - Delivery logs are created for each FCM attempt
#   - Failed pushes to fake tokens are logged as FAILED
#   - Email fallback is attempted when all FCM devices fail
#   - notification_delivery_logs table is populated correctly
# Usage: BASE_URL=http://localhost:8080 bash test_fcm_retry_fallback.sh
#
# #140 (2026-08-19): fallback is now gated to priority="critical" only (per AC — was previously
# unconditional for every eventType, a real gap). RANDOM_CHECK_SENT is the only eventType at
# "critical" in NotificationEventTypeCatalog, so test 2 must use it (an arbitrary/unknown
# eventType like the old "TEST_RETRY" resolves to the "normal" fallback priority and will
# correctly NOT trigger email fallback anymore).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
NOTIFICATIONS_INTERNAL_SECRET="${NOTIFICATIONS_INTERNAL_SECRET:-fams_notifications_secret_local_dev}"
PASS=0
FAIL=0

# ── Helpers ───────────────────────────────────────────────────────────────────

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
        echo "PASS: $name"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — did not contain '$needle'"
        echo "  Context: $(echo "$haystack" | head -c 300)"
        FAIL=$((FAIL + 1))
    fi
}

check_gte() {
    local name="$1" actual="$2" min="$3"
    if [ "$actual" -ge "$min" ]; then
        echo "PASS: $name ($actual >= $min)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected >= $min, got $actual"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== FCM Retry & Fallback Tests (Task 140) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

# Create tenant + employee
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"ownerEmail\":\"admin@fams.com\",\"name\":\"Retry Corp ${TS}\",\"slug\":\"retry-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_EMAIL="retry.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"Retry\",\"lastName\":\"Emp\"}"

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
EMP_USER_ID=$(curl -s "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $EMP_TOKEN" \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup: TENANT=$TENANT_ID EMP_USER=$EMP_USER_ID"
echo ""

# ── 1. Register a FAKE device token so FCM will fail ─────────────────────────
echo "--- 1. Register fake device (triggers retry + fallback) ---"
FAKE_TOKEN="fake-device-token-for-retry-test-${TS}"
curl -s -o /dev/null \
    -X POST "$BASE_URL/api/v1/me/devices" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"deviceToken\":\"$FAKE_TOKEN\",\"platform\":\"FCM\"}"
echo "Fake device registered: $FAKE_TOKEN"

# ── 2. Send a notification — FCM will fail, email fallback should trigger ─────
echo ""
echo "--- 2. Create notification (FCM fails → email fallback) ---"
notif_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/internal/notifications" \
    -H "Content-Type: application/json" \
    -H "X-Internal-Secret: $NOTIFICATIONS_INTERNAL_SECRET" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"userId\":\"$EMP_USER_ID\",\"eventType\":\"RANDOM_CHECK_SENT\",\"title\":\"Retry Test\",\"body\":\"This tests retry and fallback\"}")
notif_status=$(echo "$notif_resp" | tail -n 1)
notif_body=$(echo "$notif_resp" | head -n -1)

if [ "$notif_status" -eq 200 ] || [ "$notif_status" -eq 201 ]; then
    echo "PASS: Notification created (HTTP $notif_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Notification creation returned $notif_status"
    FAIL=$((FAIL + 1))
fi
NOTIF_ID=$(echo "$notif_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Notification ID: $NOTIF_ID"

# Allow a moment for any async processing
sleep 2

# ── 3. Verify delivery logs were written to the database ─────────────────────
echo ""
echo "--- 3. Verify delivery logs in database ---"
log_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM notification_delivery_logs WHERE notification_id='$NOTIF_ID';" \
    | tr -d ' \n')

check_gte "At least 1 delivery log created for this notification" "$log_count" "1"

# Check that at least one log entry has status FAILED (FCM failed with fake token)
failed_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM notification_delivery_logs WHERE notification_id='$NOTIF_ID' AND status='FAILED';" \
    | tr -d ' \n')
check_gte "At least 1 FAILED delivery log entry" "$failed_count" "1"

# Check that the attempt_number is recorded
attempt_nums=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT attempt_number FROM notification_delivery_logs WHERE notification_id='$NOTIF_ID' AND status='FAILED' LIMIT 1;" \
    | tr -d ' \n')
check_gte "FAILED log has attempt_number >= 1" "$attempt_nums" "1"

# Check FCM channel is recorded
channel=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT channel FROM notification_delivery_logs WHERE notification_id='$NOTIF_ID' AND status='FAILED' LIMIT 1;" \
    | tr -d ' \n')
check_val "FAILED log channel is FCM" "$channel" "FCM"

# Check device_token is recorded
token_logged=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT device_token FROM notification_delivery_logs WHERE notification_id='$NOTIF_ID' AND status='FAILED' LIMIT 1;" \
    | tr -d ' \n')
check_val "FAILED log has the device token" "$token_logged" "$FAKE_TOKEN"

# Check error_message is populated
err_msg=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT error_message IS NOT NULL FROM notification_delivery_logs WHERE notification_id='$NOTIF_ID' AND status='FAILED' LIMIT 1;" \
    | tr -d ' \n')
check_val "FAILED log has error_message" "$err_msg" "t"

# ── 4. Check for email fallback log entry ─────────────────────────────────────
echo ""
echo "--- 4. Email fallback log entry ---"
fallback_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM notification_delivery_logs WHERE notification_id='$NOTIF_ID' AND channel='EMAIL_FALLBACK';" \
    | tr -d ' \n')
# Fallback fires when all devices fail — should have at least one entry
check_gte "Email fallback log entry created" "$fallback_count" "1"

fallback_status=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM notification_delivery_logs WHERE notification_id='$NOTIF_ID' AND channel='EMAIL_FALLBACK' LIMIT 1;" \
    | tr -d ' \n')
# Fallback status is either FALLBACK_EMAIL_SENT or FALLBACK_EMAIL_FAILED depending on mail config
if [ "$fallback_status" = "FALLBACK_EMAIL_SENT" ] || [ "$fallback_status" = "FALLBACK_EMAIL_FAILED" ]; then
    echo "PASS: Fallback status is a valid value ($fallback_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Unexpected fallback status '$fallback_status'"
    FAIL=$((FAIL + 1))
fi

# ── 5. notification_delivery_logs table structure check ───────────────────────
echo ""
echo "--- 5. Table structure sanity check ---"
col_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM information_schema.columns WHERE table_name='notification_delivery_logs';" \
    | tr -d ' \n')
check_gte "notification_delivery_logs has at least 8 columns" "$col_count" "8"

# ── 6. In-app notification is still created even when FCM fails ───────────────
echo ""
echo "--- 6. In-app notification created despite FCM failure ---"
inbox=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/notifications" \
    -H "Authorization: Bearer $EMP_TOKEN")
check_contains "Employee inbox has the notification" "$inbox" '"RANDOM_CHECK_SENT"'

# ── 7. Zero-device user still gets email fallback (#140, 2026-08-22 follow-up) ─
# Found via a live support case: an employee who never opened/logged into the mobile app has
# ZERO rows in user_devices at all (not even a dead/fake one). The old code's early return for
# devices.isEmpty() skipped the fallback check entirely — this employee got no push AND no email.
echo ""
echo "--- 7. Zero-device user (never registered any device) still gets email fallback ---"
ZD_EMAIL="zerodev.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$ZD_EMAIL\",\"firstName\":\"ZeroDev\",\"lastName\":\"Emp\"}"

ZD_INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$ZD_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$ZD_INV_TOKEN\",\"password\":\"Employee@1234\"}"

zd_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$ZD_EMAIL\",\"password\":\"Employee@1234\"}")
ZD_TOKEN=$(echo "$zd_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
ZD_USER_ID=$(curl -s "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $ZD_TOKEN" \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# No POST to /api/v1/me/devices here — this employee has literally zero device rows.
zd_devices=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM user_devices WHERE user_id='$ZD_USER_ID';" | tr -d ' \n')
check_val "Employee has zero registered devices (test precondition)" "$zd_devices" "0"

zd_notif_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/internal/notifications" \
    -H "Content-Type: application/json" \
    -H "X-Internal-Secret: $NOTIFICATIONS_INTERNAL_SECRET" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"userId\":\"$ZD_USER_ID\",\"eventType\":\"RANDOM_CHECK_SENT\",\"title\":\"Zero Device Test\",\"body\":\"This tests fallback with no devices at all\"}")
ZD_NOTIF_ID=$(echo "$zd_notif_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
sleep 2

zd_fcm_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM notification_delivery_logs WHERE notification_id='$ZD_NOTIF_ID' AND channel='FCM';" \
    | tr -d ' \n')
check_val "No FCM log rows (no devices to attempt)" "$zd_fcm_count" "0"

zd_fallback_status=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM notification_delivery_logs WHERE notification_id='$ZD_NOTIF_ID' AND channel='EMAIL_FALLBACK' LIMIT 1;" \
    | tr -d ' \n')
if [ "$zd_fallback_status" = "FALLBACK_EMAIL_SENT" ] || [ "$zd_fallback_status" = "FALLBACK_EMAIL_FAILED" ]; then
    echo "PASS: Zero-device user still got an email fallback attempt ($zd_fallback_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Zero-device user got no email fallback log entry at all (status='$zd_fallback_status')"
    FAIL=$((FAIL + 1))
fi

zd_inbox=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/notifications" \
    -H "Authorization: Bearer $ZD_TOKEN")
check_contains "Zero-device employee inbox still has the in-app notification" "$zd_inbox" '"RANDOM_CHECK_SENT"'

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "============================================"
echo "Results: $PASS passed, $FAIL failed"
echo "============================================"
[ "$FAIL" -eq 0 ]
