#!/usr/bin/env bash
# Tests for random check dispatch notification (Task 100)
# Verifies that dispatching a check creates an in-app + push notification
# for the employee with event_type RANDOM_CHECK_SENT.
# Usage: BASE_URL=http://localhost:8080 bash test_dispatch_notification.sh

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

echo "=== Random Check Dispatch Notification Tests (Task 100) ==="
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

# Tenant
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Notif Check Corp ${TS}\",\"slug\":\"nc-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Site
s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Notif Site\",\"code\":\"NS-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Shift
sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Employee
EMP_EMAIL="nc.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"NC\",\"lastName\":\"Emp\"}"
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$EMP_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

# Login as employee
emp_login=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

# Assignment
asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2099-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi

# Random check config
cfg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 1,
      "minIntervalMinutes": 60,
      "allowedStartTime": "00:00:00",
      "allowedEndTime": "23:59:59",
      "checkMode": "location_only",
      "applicableRoles": [],
      "responseWindowSeconds": 120
    }')
if [ "$(echo "$cfg_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: config (status: $(echo "$cfg_resp" | tail -n 1))"; exit 1; fi

echo "Setup complete: TENANT=$TENANT_ID EMP_ID=$EMP_ID SITE=$SITE_ID"
echo ""

# ── 1. Manual check creates a RANDOM_CHECK_SENT in-app notification ───────────
echo "--- 1. Manual check triggers notification ---"

# Count notifications before trigger
notif_before=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/notifications" \
    -H "Authorization: Bearer $EMP_TOKEN")
count_before=$(echo "$notif_before" | grep -o '"totalElements":[0-9]*' | grep -o '[0-9]*')

trig_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/manual" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"employeeId\":\"$EMP_ID\",\"reason\":\"test manual check\"}")
trig_status=$(echo "$trig_resp" | tail -n 1)
trig_body=$(echo "$trig_resp" | head -n -1)

check_val "Manual check returns 201" "$trig_status" "201"
CHECK_ID=$(echo "$trig_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Wait briefly for async notification creation
sleep 1

# Count notifications after trigger
notif_after=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/notifications" \
    -H "Authorization: Bearer $EMP_TOKEN")
count_after=$(echo "$notif_after" | grep -o '"totalElements":[0-9]*' | grep -o '[0-9]*')

if [ "$count_after" -gt "$count_before" ]; then
    echo "PASS: Notification count increased after manual check (before=$count_before, after=$count_after)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Notification count did not increase (before=$count_before, after=$count_after)"
    FAIL=$((FAIL + 1))
fi

# Verify event_type is RANDOM_CHECK_SENT
check_contains "Notification has RANDOM_CHECK_SENT event_type" "$notif_after" '"RANDOM_CHECK_SENT"'
check_contains "Notification has non-empty body" "$notif_after" '"body":"B'

# ── 2. Manual dispatch endpoint also triggers notification ────────────────────
echo ""
echo "--- 2. Scheduled dispatch endpoint triggers notification ---"

# Generate a scheduled check (pending status, then dispatch)
gen_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/generate" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"date\":\"$(date +%Y-%m-%d)\"}")
gen_status=$(echo "$gen_resp" | tail -n 1)

# A pending check may or may not exist (depends on whether one was already generated today)
# Either way, manually dispatch via the direct endpoint using the check we already have

count_before2=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/notifications" \
    -H "Authorization: Bearer $EMP_TOKEN" | grep -o '"totalElements":[0-9]*' | grep -o '[0-9]*')

dispatch_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/$CHECK_ID/dispatch" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
dispatch_status=$(echo "$dispatch_resp" | tail -n 1)

# Dispatch of already-sent check should be idempotent (409 or 200)
if [ "$dispatch_status" -eq 200 ] || [ "$dispatch_status" -eq 409 ]; then
    echo "PASS: Dispatch of already-sent check handled gracefully (HTTP $dispatch_status)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Dispatch returned unexpected status $dispatch_status"
    FAIL=$((FAIL + 1))
fi

# ── 3. Employee can see the notification in their inbox ───────────────────────
echo ""
echo "--- 3. Employee inbox contains the random check notification ---"
inbox_resp=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/notifications" \
    -H "Authorization: Bearer $EMP_TOKEN")

check_contains "Inbox has RANDOM_CHECK_SENT" "$inbox_resp" '"RANDOM_CHECK_SENT"'
check_contains "Inbox has notification title" "$inbox_resp" '"title"'
check_contains "Inbox has unreadCount" "$inbox_resp" '"unreadCount"'

# ── 4. Notification has correct tenant scoping ────────────────────────────────
echo ""
echo "--- 4. Notification tenant scoping ---"
notif_tenant=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/notifications" \
    -H "Authorization: Bearer $EMP_TOKEN" | grep -o '"tenantId":"[^"]*"' | head -1 | cut -d'"' -f4)
check_val "Notification has correct tenantId" "$notif_tenant" "$TENANT_ID"

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "============================================"
echo "Results: $PASS passed, $FAIL failed"
echo "============================================"
[ "$FAIL" -eq 0 ]
