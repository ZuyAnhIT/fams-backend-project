#!/usr/bin/env bash
# Tests for FCM device token management (Task 87)
# POST   /api/v1/me/devices
# DELETE /api/v1/me/devices/{deviceToken}
# Usage: BASE_URL=http://localhost:8080 bash test_fcm_devices.sh

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

echo "=== FCM Device Token Tests (Task 87) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: login ──────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then
    echo "SETUP FAILED: admin login"
    exit 1
fi
TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Logged in."

TS=$(date +%s)
FAKE_TOKEN="fake-fcm-token-${TS}"

# ── 1. Happy path: register a device token ────────────────────────────────────
echo ""
echo "--- 1. Register device token ---"
reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/me/devices" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"deviceToken\":\"$FAKE_TOKEN\",\"platform\":\"FCM\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
reg_body=$(echo "$reg_resp" | head -n -1)

if [ "$reg_status" -eq 200 ]; then
    echo "PASS: POST /api/v1/me/devices returns 200 (HTTP $reg_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: POST /api/v1/me/devices — expected 200, got $reg_status"
    echo "  Body: $reg_body"
    FAIL=$((FAIL + 1))
fi
check_contains "Register response has id" "$reg_body" '"id"'
check_contains "Register response has deviceToken" "$reg_body" '"deviceToken"'
check_contains "Register response has platform FCM" "$reg_body" '"FCM"'
check_contains "Register response has success:true" "$reg_body" '"success":true'

# ── 2. Re-register same token (idempotent) ────────────────────────────────────
echo ""
echo "--- 2. Re-register same token (idempotent) ---"
run_test "Re-registering same token returns 200" 200 \
    -X POST "$BASE_URL/api/v1/me/devices" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"deviceToken\":\"$FAKE_TOKEN\",\"platform\":\"FCM\"}"

# ── 3. Missing deviceToken — validation error ─────────────────────────────────
echo ""
echo "--- 3. Missing deviceToken (400) ---"
run_test "Missing deviceToken returns 400" 400 \
    -X POST "$BASE_URL/api/v1/me/devices" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"platform":"FCM"}'

# ── 4. Unauthenticated registration ──────────────────────────────────────────
echo ""
echo "--- 4. Unauthenticated (401) ---"
run_test "POST /api/v1/me/devices without token returns 401" 401 \
    -X POST "$BASE_URL/api/v1/me/devices" \
    -H "Content-Type: application/json" \
    -d "{\"deviceToken\":\"$FAKE_TOKEN\"}"

# ── 5. Unregister device token ────────────────────────────────────────────────
echo ""
echo "--- 5. Unregister device token ---"
run_test "DELETE /api/v1/me/devices/{token} returns 204" 204 \
    -X DELETE "$BASE_URL/api/v1/me/devices/$FAKE_TOKEN" \
    -H "Authorization: Bearer $TOKEN"

# ── 6. Unregister already-deleted token is idempotent (204) ──────────────────
echo ""
echo "--- 6. Delete non-existent token is idempotent ---"
run_test "DELETE /api/v1/me/devices/{nonexistent} returns 204" 204 \
    -X DELETE "$BASE_URL/api/v1/me/devices/non-existent-token-${TS}" \
    -H "Authorization: Bearer $TOKEN"

# ── 7. Unauthenticated unregister ─────────────────────────────────────────────
echo ""
echo "--- 7. Unauthenticated unregister (401) ---"
run_test "DELETE /api/v1/me/devices/{token} without token returns 401" 401 \
    -X DELETE "$BASE_URL/api/v1/me/devices/$FAKE_TOKEN"

# ── 8. Send push via internal notification endpoint (exercises the full chain) ─
echo ""
echo "--- 8. Create in-app notification (exercises push path) ---"
TS2=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"name\":\"FCM Corp ${TS2}\",\"slug\":\"fcm-corp-${TS2}\"}")
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_EMAIL="fcm.emp.${TS2}@example.com"
curl -s -o /dev/null \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"FCM\",\"lastName\":\"Emp\"}"

inv_page=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Authorization: Bearer $TOKEN")
INV_TOKEN=$(echo "$inv_page" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)

curl -s -o /dev/null \
    -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

emp_login=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
EMP_USER_ID=$(curl -s "$BASE_URL/api/v1/auth/me" \
    -H "Authorization: Bearer $EMP_TOKEN" \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Register a (fake) device token for the employee
FAKE_EMP_TOKEN="fake-emp-device-${TS2}"
curl -s -o /dev/null \
    -X POST "$BASE_URL/api/v1/me/devices" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"deviceToken\":\"$FAKE_EMP_TOKEN\",\"platform\":\"FCM\"}"

# Send notification through internal endpoint — this triggers both in-app + push path
notif_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/internal/notifications" \
    -H "Content-Type: application/json" \
    -H "X-Internal-Secret: $NOTIFICATIONS_INTERNAL_SECRET" \
    -d "{\"tenantId\":\"$TENANT_ID\",\"userId\":\"$EMP_USER_ID\",\"eventType\":\"TEST\",\"title\":\"Test Push\",\"body\":\"FCM test\"}")
notif_status=$(echo "$notif_resp" | tail -n 1)
notif_body=$(echo "$notif_resp" | head -n -1)

if [ "$notif_status" -eq 200 ] || [ "$notif_status" -eq 201 ]; then
    echo "PASS: POST /internal/notifications (full push chain) returns $notif_status"
    PASS=$((PASS + 1))
else
    echo "FAIL: POST /internal/notifications — expected 200/201, got $notif_status"
    echo "  Body: $notif_body"
    FAIL=$((FAIL + 1))
fi
check_contains "Notification response has id" "$notif_body" '"id"'

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "============================================"
echo "Results: $PASS passed, $FAIL failed"
echo "============================================"
[ "$FAIL" -eq 0 ]
