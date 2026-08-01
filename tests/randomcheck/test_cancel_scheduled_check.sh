#!/usr/bin/env bash
# Tests for cancelling scheduled checks (task 99)
# POST /api/v1/tenants/{tenantId}/scheduled-checks/{checkId}/cancel
# Auto-cancel on assignment cancellation
# Usage: BASE_URL=http://localhost:8080 bash test_cancel_scheduled_check.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
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
    local name="$1"
    local actual="$2"
    local expected="$3"
    if [ "$actual" = "$expected" ]; then
        echo "PASS: $name (=$expected)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected '$expected', got '$actual'"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Cancel Scheduled Check Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: platform admin login ───────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

# Tenant
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Cancel Corp ${TS}\",\"slug\":\"cancel-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Site
s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Cancel Site\",\"code\":\"CS-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Shift
sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Employee
INVITE_EMAIL="cancel.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Cancel\",\"lastName\":\"Emp\"}"
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"
EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

# Assignment
asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi
ASGN_ID=$(echo "$asgn_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Config
cfg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 2,
      "minIntervalMinutes": 60,
      "allowedStartTime": "09:00:00",
      "allowedEndTime": "16:00:00",
      "checkMode": "location_only",
      "applicableRoles": [],
      "responseWindowSeconds": 300
    }')
if [ "$(echo "$cfg_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: config"; exit 1; fi

# Generate checks
TODAY=$(date +%Y-%m-%d)
gen_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/generate" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"date\":\"$TODAY\"}")
if [ "$(echo "$gen_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: generate"; exit 1; fi

CHECK_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT id FROM scheduled_checks WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY' AND deleted_at IS NULL ORDER BY check_index LIMIT 1;" \
    | tr -d ' \n')
CHECK_ID2=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT id FROM scheduled_checks WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY' AND deleted_at IS NULL ORDER BY check_index OFFSET 1 LIMIT 1;" \
    | tr -d ' \n')
echo "Setup complete. Check1=$CHECK_ID Check2=$CHECK_ID2"
echo ""

BASE_CHECKS="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks"

# ── Test 1: Cancel endpoint returns 200 ───────────────────────────────────────
echo "--- Test 1: Cancel pending check returns 200 ---"
cancel_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$CHECK_ID/cancel" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
cancel_status=$(echo "$cancel_resp" | tail -n 1)
cancel_body=$(echo "$cancel_resp" | head -n -1)
if [ "$cancel_status" -eq 200 ]; then
    echo "PASS: Cancel returns 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Cancel returned $cancel_status"
    echo "Body: $cancel_body"
    FAIL=$((FAIL + 1))
fi

# ── Test 2: Response contains cancelled=true ───────────────────────────────────
echo ""
echo "--- Test 2: Response body has cancelled=true ---"
if echo "$cancel_body" | grep -q '"cancelled":true'; then
    echo "PASS: Response has cancelled=true"
    PASS=$((PASS + 1))
else
    echo "FAIL: cancelled=true not found in: $cancel_body"
    FAIL=$((FAIL + 1))
fi

# ── Test 3: Status in DB changes to 'cancelled' ───────────────────────────────
echo ""
echo "--- Test 3: Status in DB is 'cancelled' ---"
db_status=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM scheduled_checks WHERE id='$CHECK_ID';" \
    | tr -d ' \n')
check_val "DB status after cancel" "$db_status" "cancelled"

# ── Test 4: Cancelled check removed from Redis queue ─────────────────────────
echo ""
echo "--- Test 4: Cancelled check is no longer in dispatch queue ---"
queue_resp=$(curl -s \
    -X GET "$BASE_CHECKS/dispatch-queue?checkId=$CHECK_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
in_queue=$(echo "$queue_resp" | grep -o '"inQueue":[a-z]*' | cut -d: -f2)
check_val "Check removed from Redis queue" "$in_queue" "false"

# ── Test 5: Second check still pending (only first was cancelled) ─────────────
echo ""
echo "--- Test 5: Other check is still pending ---"
db_status2=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM scheduled_checks WHERE id='$CHECK_ID2';" \
    | tr -d ' \n')
check_val "Second check still pending" "$db_status2" "pending"

# ── Test 6: Re-cancelling an already-cancelled check returns 400 ──────────────
echo ""
echo "--- Test 6: Cancelling an already-cancelled check returns 400 ---"
run_test "Re-cancel returns 400" 400 \
    -X POST "$BASE_CHECKS/$CHECK_ID/cancel" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# ── Test 7: Cancel a non-existent check returns 404 ──────────────────────────
echo ""
echo "--- Test 7: Cancel non-existent check returns 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000099"
run_test "Cancel non-existent returns 404" 404 \
    -X POST "$BASE_CHECKS/$FAKE_ID/cancel" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# ── Test 8: Cancel without auth returns 401 ───────────────────────────────────
echo ""
echo "--- Test 8: Cancel without auth returns 401 ---"
run_test "No token returns 401" 401 \
    -X POST "$BASE_CHECKS/$CHECK_ID2/cancel"

# ── Test 9: Employee without permission gets 403 ─────────────────────────────
echo ""
echo "--- Test 9: Employee without randomchecks:configure gets 403 ---"
UNAUTH_EMAIL="cancel.unauth.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$UNAUTH_EMAIL\",\"firstName\":\"Unauth\",\"lastName\":\"User\"}"
UINV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$UNAUTH_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$UINV_TOKEN\",\"password\":\"Employee@1234\"}"
ulogin_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$UNAUTH_EMAIL\",\"password\":\"Employee@1234\"}")
USER_TOKEN=$(echo "$ulogin_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "No permission returns 403" 403 \
    -X POST "$BASE_CHECKS/$CHECK_ID2/cancel" \
    -H "Authorization: Bearer $USER_TOKEN"

# ── Test 10: Cancel a 'sent' check is allowed ────────────────────────────────
echo ""
echo "--- Test 10: Cancel a 'sent' check returns 200 ---"
# Dispatch check2 first to make it 'sent'
curl -s -o /dev/null -X POST "$BASE_CHECKS/$CHECK_ID2/dispatch" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
sent_status=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM scheduled_checks WHERE id='$CHECK_ID2';" \
    | tr -d ' \n')
if [ "$sent_status" != "sent" ]; then echo "SETUP FAILED: check2 should be sent but is $sent_status"; exit 1; fi

cancel2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$CHECK_ID2/cancel" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
cancel2_status=$(echo "$cancel2_resp" | tail -n 1)
db_status_after=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM scheduled_checks WHERE id='$CHECK_ID2';" \
    | tr -d ' \n')
if [ "$cancel2_status" -eq 200 ] && [ "$db_status_after" = "cancelled" ]; then
    echo "PASS: Sent check cancelled successfully"
    PASS=$((PASS + 1))
else
    echo "FAIL: cancel2_status=$cancel2_status db_status=$db_status_after"
    FAIL=$((FAIL + 1))
fi

# ── Test 11: Auto-cancel on assignment cancellation ───────────────────────────
echo ""
echo "--- Test 11: Cancelling an assignment auto-cancels its pending checks ---"
# Create a new assignment with fresh checks so there are pending ones to cancel
INVITE3="cancel.emp3.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE3\",\"firstName\":\"Emp3\",\"lastName\":\"User\"}"
INV3_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE3' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV3_TOKEN\",\"password\":\"Employee@1234\"}"
EMP3_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$INVITE3' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

asgn3_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP3_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
ASGN3_ID=$(echo "$asgn3_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

TOMORROW=$(date -d "+1 day" +%Y-%m-%d 2>/dev/null || date -v+1d +%Y-%m-%d)
curl -s -o /dev/null -X POST "$BASE_CHECKS/generate" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"date\":\"$TOMORROW\"}"

pending_before=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM scheduled_checks WHERE assignment_id='$ASGN3_ID' AND status='pending' AND deleted_at IS NULL;" \
    | tr -d ' \n')
echo "Pending checks before cancel: $pending_before"

# Cancel the assignment
cancel_asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X DELETE "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments/$ASGN3_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
cancel_asgn_status=$(echo "$cancel_asgn_resp" | tail -n 1)

pending_after=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM scheduled_checks WHERE assignment_id='$ASGN3_ID' AND status='pending' AND deleted_at IS NULL;" \
    | tr -d ' \n')
cancelled_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM scheduled_checks WHERE assignment_id='$ASGN3_ID' AND status='cancelled' AND deleted_at IS NULL;" \
    | tr -d ' \n')

if [ "$cancel_asgn_status" -eq 204 ] && [ "$pending_after" -eq 0 ] && [ "$cancelled_count" -eq "$pending_before" ]; then
    echo "PASS: Assignment cancelled, $cancelled_count check(s) auto-cancelled, 0 still pending"
    PASS=$((PASS + 1))
else
    echo "FAIL: cancel_status=$cancel_asgn_status pending_before=$pending_before pending_after=$pending_after cancelled=$cancelled_count"
    FAIL=$((FAIL + 1))
fi

# ── Test 12: Auto-cancelled checks are removed from Redis queue ──────────────
echo ""
echo "--- Test 12: Auto-cancelled checks not in dispatch queue ---"
ASGN3_CHECK=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT id FROM scheduled_checks WHERE assignment_id='$ASGN3_ID' AND status='cancelled' LIMIT 1;" \
    | tr -d ' \n')
if [ -n "$ASGN3_CHECK" ]; then
    q3_resp=$(curl -s \
        -X GET "$BASE_CHECKS/dispatch-queue?checkId=$ASGN3_CHECK" \
        -H "Authorization: Bearer $ADMIN_TOKEN")
    in_q3=$(echo "$q3_resp" | grep -o '"inQueue":[a-z]*' | cut -d: -f2)
    check_val "Auto-cancelled check not in queue" "$in_q3" "false"
else
    echo "SKIP: No auto-cancelled check found"
    FAIL=$((FAIL + 1))
fi

# ── Test 13: List endpoint reflects cancelled status ─────────────────────────
echo ""
echo "--- Test 13: List shows cancelled status for manually cancelled check ---"
list_resp=$(curl -s \
    -X GET "$BASE_CHECKS?date=$TODAY" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
cancelled_in_list=$(echo "$list_resp" | grep -o '"status":"cancelled"' | wc -l | tr -d ' ')
if [ "$cancelled_in_list" -ge 1 ]; then
    echo "PASS: List shows $cancelled_in_list cancelled check(s)"
    PASS=$((PASS + 1))
else
    echo "FAIL: No cancelled checks in list"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
