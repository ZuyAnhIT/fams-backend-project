#!/usr/bin/env bash
# Tests for the Redis dispatch queue and dispatch mechanism (task 98)
# POST /api/v1/tenants/{tenantId}/scheduled-checks/generate
# GET  /api/v1/tenants/{tenantId}/scheduled-checks/dispatch-queue
# POST /api/v1/tenants/{tenantId}/scheduled-checks/{checkId}/dispatch
# Usage: BASE_URL=http://localhost:8080 bash test_dispatch_job.sh

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

check_body() {
    local name="$1"
    local body="$2"
    local pattern="$3"
    if echo "$body" | grep -q "$pattern"; then
        echo "PASS: $name"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — pattern '$pattern' not found in: $body"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Dispatch Queue & Dispatch Mechanism Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: platform admin login ───────────────────────────────────────────────
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: admin login ($login_status)"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."

# ── Setup: tenant ─────────────────────────────────────────────────────────────
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Dispatch Corp ${TS}\",\"slug\":\"dispatch-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant: $TENANT_ID"

# ── Setup: site ───────────────────────────────────────────────────────────────
s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Dispatch Site ${TS}\",\"code\":\"DS-${TS}\",\"address\":\"1 Main St\",\"timezone\":\"Asia/Ho_Chi_Minh\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Site: $SITE_ID"

# ── Setup: shift ──────────────────────────────────────────────────────────────
sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Shift: $SHIFT_ID"

# ── Setup: employee ───────────────────────────────────────────────────────────
INVITE_EMAIL="disp.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Disp\",\"lastName\":\"Emp\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
if [ -z "$INV_TOKEN" ]; then echo "SETUP FAILED: invitation token"; exit 1; fi
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"
EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
if [ -z "$EMP_ID" ]; then echo "SETUP FAILED: employee id"; exit 1; fi
echo "Employee: $EMP_ID"

# ── Setup: assignment ─────────────────────────────────────────────────────────
TODAY=$(date +%Y-%m-%d)
asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi
ASGN_ID=$(echo "$asgn_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Assignment: $ASGN_ID"

# ── Setup: config (2 checks) ─────────────────────────────────────────────────
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
echo "Config created."
echo ""

BASE_CHECKS="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks"

# ── Test 1: Generate checks — should enqueue them in Redis ───────────────────
echo "--- Test 1: Trigger generation — returns 200 with created count ---"
gen_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/generate" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"date\":\"$TODAY\"}")
gen_status=$(echo "$gen_resp" | tail -n 1)
gen_body=$(echo "$gen_resp" | head -n -1)
if [ "$gen_status" -eq 200 ]; then
    created=$(echo "$gen_body" | grep -o '"created":[0-9]*' | cut -d: -f2)
    echo "PASS: Generation returns 200, created=$created"
    PASS=$((PASS + 1))
else
    echo "FAIL: Generation returned HTTP $gen_status"
    echo "Body: $gen_body"
    FAIL=$((FAIL + 1))
fi

# Retrieve the first generated check ID for subsequent tests
CHECK_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT id FROM scheduled_checks WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY' AND deleted_at IS NULL ORDER BY check_index LIMIT 1;" \
    | tr -d ' \n')
CHECK_ID2=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT id FROM scheduled_checks WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY' AND deleted_at IS NULL ORDER BY check_index OFFSET 1 LIMIT 1;" \
    | tr -d ' \n')
if [ -z "$CHECK_ID" ]; then echo "SETUP FAILED: no check IDs found"; exit 1; fi
echo "Check 1: $CHECK_ID"
echo "Check 2: $CHECK_ID2"

echo ""
echo "--- Test 2: Dispatch queue endpoint returns 200 ---"
run_test "GET /dispatch-queue returns 200" 200 \
    -X GET "$BASE_CHECKS/dispatch-queue" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "--- Test 3: Queue size is positive after generation ---"
queue_resp=$(curl -s \
    -X GET "$BASE_CHECKS/dispatch-queue" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
queue_size=$(echo "$queue_resp" | grep -o '"queueSize":[0-9]*' | cut -d: -f2)
if [ "${queue_size:-0}" -ge 2 ]; then
    echo "PASS: Queue size is $queue_size (≥2 after generating 2 checks)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Queue size is $queue_size (expected ≥2)"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 4: Check ID appears in queue (inQueue=true) ---"
q_check_resp=$(curl -s \
    -X GET "$BASE_CHECKS/dispatch-queue?checkId=$CHECK_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
in_queue=$(echo "$q_check_resp" | grep -o '"inQueue":[a-z]*' | cut -d: -f2)
if [ "$in_queue" = "true" ]; then
    echo "PASS: Check $CHECK_ID is inQueue=true"
    PASS=$((PASS + 1))
else
    echo "FAIL: Check not found in queue — inQueue=$in_queue"
    echo "Response: $q_check_resp"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 5: scheduledEpochSeconds is present and non-null ---"
has_epoch=$(echo "$q_check_resp" | grep -o '"scheduledEpochSeconds":[0-9.]*')
if [ -n "$has_epoch" ]; then
    echo "PASS: scheduledEpochSeconds present: $has_epoch"
    PASS=$((PASS + 1))
else
    echo "FAIL: scheduledEpochSeconds missing or null in: $q_check_resp"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 6: Manual dispatch endpoint returns 200 ---"
dispatch_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$CHECK_ID/dispatch" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
dispatch_status=$(echo "$dispatch_resp" | tail -n 1)
dispatch_body=$(echo "$dispatch_resp" | head -n -1)
if [ "$dispatch_status" -eq 200 ]; then
    echo "PASS: Manual dispatch returns 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Manual dispatch returned $dispatch_status"
    echo "Body: $dispatch_body"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 7: Dispatch response contains dispatched=true ---"
check_body "Dispatch response has dispatched=true" "$dispatch_body" '"dispatched":true'

echo ""
echo "--- Test 8: Check status changes to 'sent' after dispatch ---"
status_in_db=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM scheduled_checks WHERE id='$CHECK_ID';" \
    | tr -d ' \n')
if [ "$status_in_db" = "sent" ]; then
    echo "PASS: Check status is 'sent' in DB"
    PASS=$((PASS + 1))
else
    echo "FAIL: Check status is '$status_in_db' (expected 'sent')"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 9: Dispatching already-sent check is idempotent (returns 200, no status change) ---"
dispatch2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$CHECK_ID/dispatch" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
dispatch2_status=$(echo "$dispatch2_resp" | tail -n 1)
status_after=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM scheduled_checks WHERE id='$CHECK_ID';" \
    | tr -d ' \n')
if [ "$dispatch2_status" -eq 200 ] && [ "$status_after" = "sent" ]; then
    echo "PASS: Re-dispatch returns 200, status remains 'sent'"
    PASS=$((PASS + 1))
else
    echo "FAIL: dispatch2_status=$dispatch2_status status_after=$status_after"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 10: Dispatch non-existent check returns 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000099"
run_test "Dispatch non-existent check returns 404" 404 \
    -X POST "$BASE_CHECKS/$FAKE_ID/dispatch" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

echo ""
echo "--- Test 11: Second check is still pending (only dispatched the first) ---"
status2_in_db=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM scheduled_checks WHERE id='$CHECK_ID2';" \
    | tr -d ' \n')
if [ "$status2_in_db" = "pending" ]; then
    echo "PASS: Second check still 'pending' (untouched)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Second check status is '$status2_in_db' (expected 'pending')"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 12: Dispatch queue endpoint requires auth ---"
run_test "GET /dispatch-queue without token returns 401" 401 \
    -X GET "$BASE_CHECKS/dispatch-queue"

echo ""
echo "--- Test 13: Manual dispatch requires auth ---"
run_test "POST /{checkId}/dispatch without token returns 401" 401 \
    -X POST "$BASE_CHECKS/$CHECK_ID/dispatch"

echo ""
echo "--- Test 14: Non-admin user without permission gets 403 on dispatch ---"
# Create an employee user for this tenant and try to dispatch
UNAUTH_EMAIL="unauth.${TS}@example.com"
uinv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$UNAUTH_EMAIL\",\"firstName\":\"Unauth\",\"lastName\":\"User\"}")
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
if [ -n "$USER_TOKEN" ]; then
    run_test "Employee without randomchecks:configure gets 403 on dispatch" 403 \
        -X POST "$BASE_CHECKS/$CHECK_ID/dispatch" \
        -H "Authorization: Bearer $USER_TOKEN"
else
    echo "SKIP: Could not obtain employee token (FAIL)"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 15: List endpoint shows updated status 'sent' for dispatched check ---"
list_resp=$(curl -s \
    -X GET "$BASE_CHECKS?date=$TODAY" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
# Extract status of our dispatched check from the list
sent_count=$(echo "$list_resp" | grep -o '"status":"sent"' | wc -l | tr -d ' ')
if [ "$sent_count" -ge 1 ]; then
    echo "PASS: List shows $sent_count check(s) with status 'sent'"
    PASS=$((PASS + 1))
else
    echo "FAIL: No 'sent' checks in list response"
    echo "Body: $list_resp"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
