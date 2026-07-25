#!/usr/bin/env bash
# Tests for late response rejection (task 105)
# POST /api/v1/tenants/{tenantId}/scheduled-checks/{checkId}/respond
# Returns 410 Gone when now > expires_at
# Usage: BASE_URL=http://localhost:8080 bash test_late_response_rejection.sh

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

echo "=== Late Response Rejection Tests (task 105) ==="
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
    -d "{\"name\":\"Respond Corp ${TS}\",\"slug\":\"respond-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Site
s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Respond Site\",\"code\":\"RS-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Shift
sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Employee
EMP_EMAIL="respond.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"Resp\",\"lastName\":\"Emp\"}"
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"
EMP_LOGIN=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
EMP_TOKEN=$(echo "$EMP_LOGIN" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$EMP_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

# Assignment
asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi
ASGN_ID=$(echo "$asgn_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Config with 1-second response window (to test expiry easily)
cfg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 1,
      "minIntervalMinutes": 60,
      "allowedStartTime": "09:00:00",
      "allowedEndTime": "16:00:00",
      "checkMode": "location_only",
      "applicableRoles": [],
      "responseWindowSeconds": 300
    }')
if [ "$(echo "$cfg_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: config"; exit 1; fi

TODAY=$(date +%Y-%m-%d)
BASE_CHECKS="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks"

echo "Setup complete. TENANT=$TENANT_ID EMP=$EMP_ID"
echo ""

# ── Helpers: directly insert a check with controllable expires_at ─────────────
# We use DB direct insert so we can control scheduled_at/expires_at precisely.
# The index is computed from the DB max to avoid unique-constraint conflicts.

insert_check() {
    local scheduled_offset="$1"   # e.g. '- interval ''10 minutes'''
    local expires_offset="$2"     # e.g. '- interval ''1 second''' or '+ interval ''5 minutes'''
    local cfg_id
    cfg_id=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT id FROM random_check_configs WHERE tenant_id='$TENANT_ID' AND site_id IS NULL AND deleted_at IS NULL LIMIT 1;" \
        | tr -d ' \n')
    local snapshot="{\"configId\":\"$cfg_id\",\"checkMode\":\"location_only\"}"
    docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "WITH next_idx AS (
           SELECT COALESCE(MAX(check_index), 0) + 1 AS idx
           FROM scheduled_checks
           WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY'
         ),
         ins AS (
           INSERT INTO scheduled_checks
             (id, tenant_id, assignment_id, employee_id, site_id, shift_id, config_id,
              config_snapshot, check_date, check_index, scheduled_at, expires_at, status, created_at, updated_at)
           SELECT
             gen_random_uuid(), '$TENANT_ID', '$ASGN_ID', '$EMP_ID', '$SITE_ID', '$SHIFT_ID', '$cfg_id',
             '$snapshot'::jsonb, '$TODAY', idx,
             now() $scheduled_offset, now() $expires_offset, 'sent', now(), now()
           FROM next_idx
           RETURNING id
         ) SELECT id FROM ins;" \
        | tr -d ' \n'
}

# ── Test 1: Valid on-time response returns 200 ────────────────────────────────
echo "--- Test 1: Valid response within window returns 200 ---"
# Insert a check that expires 5 minutes from now
VALID_CHECK_ID=$(insert_check "- interval '2 minutes'" "+ interval '5 minutes'")
echo "Valid check: $VALID_CHECK_ID"

valid_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_CHECKS/$VALID_CHECK_ID/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.762622,"longitude":106.660172}')
valid_status=$(echo "$valid_resp" | tail -n 1)
valid_body=$(echo "$valid_resp" | head -n -1)
if [ "$valid_status" -eq 200 ]; then
    echo "PASS: On-time response returns 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 200, got $valid_status"
    echo "Body: $valid_body"
    FAIL=$((FAIL + 1))
fi

# ── Test 2: Response has expected fields ──────────────────────────────────────
echo ""
echo "--- Test 2: Response body contains outcome and location fields ---"
has_outcome=$(echo "$valid_body" | grep -o '"outcome":"[^"]*"' | head -1)
has_lat=$(echo "$valid_body" | grep -o '"latitude":[0-9.]*' | head -1)
if [ -n "$has_outcome" ] && [ -n "$has_lat" ]; then
    echo "PASS: Response body has outcome ($has_outcome) and latitude ($has_lat)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Missing outcome or latitude in: $valid_body"
    FAIL=$((FAIL + 1))
fi

# ── Test 3: Check status updated to 'responded' ───────────────────────────────
echo ""
echo "--- Test 3: Scheduled check status updated to 'responded' ---"
db_status=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM scheduled_checks WHERE id='$VALID_CHECK_ID';" \
    | tr -d ' \n')
check_val "Status after response" "$db_status" "responded"

# ── Test 4: Response record saved in DB ──────────────────────────────────────
echo ""
echo "--- Test 4: Response record persisted in check_responses table ---"
resp_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM check_responses WHERE scheduled_check_id='$VALID_CHECK_ID';" \
    | tr -d ' \n')
check_val "check_responses row count" "$resp_count" "1"

# ── Test 5: Late response returns 410 Gone (core task 105) ───────────────────
echo ""
echo "--- Test 5: Late response returns 410 Gone (expires_at in the past) ---"
EXPIRED_CHECK_ID=$(insert_check "- interval '10 minutes'" "- interval '1 second'")
echo "Expired check: $EXPIRED_CHECK_ID"
run_test "Late response returns 410" 410 \
    -X POST "$BASE_CHECKS/$EXPIRED_CHECK_ID/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.762622,"longitude":106.660172}'

# ── Test 6: Expired check status not changed ──────────────────────────────────
echo ""
echo "--- Test 6: Expired check status unchanged after rejected late response ---"
expired_status=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM scheduled_checks WHERE id='$EXPIRED_CHECK_ID';" \
    | tr -d ' \n')
check_val "Expired check status unchanged" "$expired_status" "sent"

# ── Test 7: No response record for expired check ─────────────────────────────
echo ""
echo "--- Test 7: No response row created for rejected late response ---"
expired_resp_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM check_responses WHERE scheduled_check_id='$EXPIRED_CHECK_ID';" \
    | tr -d ' \n')
check_val "No response row for expired check" "$expired_resp_count" "0"

# ── Test 8: Duplicate response returns 400 ────────────────────────────────────
echo ""
echo "--- Test 8: Duplicate response (already responded) returns 400 ---"
run_test "Duplicate response returns 400" 400 \
    -X POST "$BASE_CHECKS/$VALID_CHECK_ID/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.762622,"longitude":106.660172}'

# ── Test 9: Respond to cancelled check returns 400 ───────────────────────────
echo ""
echo "--- Test 9: Respond to cancelled check returns 400 ---"
CANCELLED_CHECK_ID=$(insert_check "- interval '2 minutes'" "+ interval '5 minutes'")
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "UPDATE scheduled_checks SET status='cancelled' WHERE id='$CANCELLED_CHECK_ID';" > /dev/null
run_test "Respond to cancelled check returns 400" 400 \
    -X POST "$BASE_CHECKS/$CANCELLED_CHECK_ID/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.762622,"longitude":106.660172}'

# ── Test 10: Respond to pending check returns 400 ────────────────────────────
echo ""
echo "--- Test 10: Respond to pending check (not yet dispatched) returns 400 ---"
PENDING_CHECK_ID=$(insert_check "- interval '2 minutes'" "+ interval '5 minutes'")
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "UPDATE scheduled_checks SET status='pending' WHERE id='$PENDING_CHECK_ID';" > /dev/null
run_test "Respond to pending check returns 400" 400 \
    -X POST "$BASE_CHECKS/$PENDING_CHECK_ID/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.762622,"longitude":106.660172}'

# ── Test 11: Respond to non-existent check returns 404 ───────────────────────
echo ""
echo "--- Test 11: Respond to non-existent check returns 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000099"
run_test "Respond to non-existent check returns 404" 404 \
    -X POST "$BASE_CHECKS/$FAKE_ID/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.762622,"longitude":106.660172}'

# ── Test 12: Missing required latitude returns 400 ───────────────────────────
echo ""
echo "--- Test 12: Missing latitude in request returns 400 ---"
ANOTHER_CHECK_ID=$(insert_check "- interval '2 minutes'" "+ interval '5 minutes'")
run_test "Missing latitude returns 400" 400 \
    -X POST "$BASE_CHECKS/$ANOTHER_CHECK_ID/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"longitude":106.660172}'

# ── Test 13: No auth returns 401 ─────────────────────────────────────────────
echo ""
echo "--- Test 13: No auth token returns 401 ---"
YET_ANOTHER=$(insert_check "- interval '2 minutes'" "+ interval '5 minutes'")
run_test "No token returns 401" 401 \
    -X POST "$BASE_CHECKS/$YET_ANOTHER/respond" \
    -H "Content-Type: application/json" \
    -d '{"latitude":10.762622,"longitude":106.660172}'

# ── Test 14: Employee cannot respond to another employee's check ──────────────
echo ""
echo "--- Test 14: Employee responding to another employee's check returns 404 ---"
# Create a second employee and try responding to first employee's check
EMP2_EMAIL="respond.emp2.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP2_EMAIL\",\"firstName\":\"Resp2\",\"lastName\":\"Emp\"}"
INV2_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP2_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV2_TOKEN\",\"password\":\"Employee@1234\"}"
EMP2_LOGIN=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMP2_EMAIL\",\"password\":\"Employee@1234\"}")
EMP2_TOKEN=$(echo "$EMP2_LOGIN" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

CROSS_CHECK_ID=$(insert_check "- interval '2 minutes'" "+ interval '5 minutes'")
run_test "Other employee's check returns 404" 404 \
    -X POST "$BASE_CHECKS/$CROSS_CHECK_ID/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_TOKEN" \
    -d '{"latitude":10.762622,"longitude":106.660172}'

echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
