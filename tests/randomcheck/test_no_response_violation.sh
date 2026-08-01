#!/usr/bin/env bash
# Tests for no_response violation creation (task 106)
# POST /api/v1/tenants/{tenantId}/scheduled-checks/process-expired
# Usage: BASE_URL=http://localhost:8080 bash test_no_response_violation.sh

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

echo "=== No-Response Violation Tests (task 106) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"NoResp Corp ${TS}\",\"slug\":\"noresp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"NoResp Site\",\"code\":\"NR-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_EMAIL="noresp.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"NoResp\",\"lastName\":\"Emp\"}"
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"
EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$EMP_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi
ASGN_ID=$(echo "$asgn_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

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
PROCESS_URL="$BASE_CHECKS/process-expired"

# Insert a check with already-expired window directly in DB
insert_expired_check() {
    local cfg_id
    cfg_id=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT id FROM random_check_configs WHERE tenant_id='$TENANT_ID' AND site_id IS NULL AND deleted_at IS NULL LIMIT 1;" \
        | tr -d ' \n')
    local snapshot="{\"configId\":\"$cfg_id\",\"checkMode\":\"location_only\"}"
    docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "WITH next_idx AS (
           SELECT COALESCE(MAX(check_index), 0) + 1 AS idx
           FROM scheduled_checks WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY'
         ),
         ins AS (
           INSERT INTO scheduled_checks
             (id, tenant_id, assignment_id, employee_id, site_id, shift_id, config_id,
              config_snapshot, check_date, check_index, scheduled_at, expires_at, status, created_at, updated_at)
           SELECT gen_random_uuid(), '$TENANT_ID', '$ASGN_ID', '$EMP_ID', '$SITE_ID', '$SHIFT_ID', '$cfg_id',
             '$snapshot'::jsonb, '$TODAY', idx,
             now() - interval '10 minutes', now() - interval '1 second',
             'sent', now(), now()
           FROM next_idx
           RETURNING id
         ) SELECT id FROM ins;" \
        | tr -d ' \n'
}

echo "Setup complete. TENANT=$TENANT_ID EMP=$EMP_ID SITE=$SITE_ID"
echo ""

# ── Test 1: process-expired endpoint returns 200 ──────────────────────────────
echo "--- Test 1: process-expired returns 200 with no expired checks yet ---"
proc0_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$PROCESS_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
proc0_status=$(echo "$proc0_resp" | tail -n 1)
proc0_body=$(echo "$proc0_resp" | head -n -1)
if [ "$proc0_status" -eq 200 ]; then
    v0=$(echo "$proc0_body" | grep -o '"violationsCreated":[0-9]*' | cut -d: -f2)
    echo "PASS: Returns 200, violationsCreated=$v0"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 200, got $proc0_status"
    FAIL=$((FAIL + 1))
fi

# ── Test 2: Insert an expired check → process → 1 violation created ───────────
echo ""
echo "--- Test 2: Expired sent check → violationsCreated=1 ---"
EXPIRED_CHECK=$(insert_expired_check)
echo "Expired check: $EXPIRED_CHECK"

proc_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$PROCESS_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
proc_body=$(echo "$proc_resp" | head -n -1)
v_created=$(echo "$proc_body" | grep -o '"violationsCreated":[0-9]*' | cut -d: -f2)
if [ "$v_created" -eq 1 ]; then
    echo "PASS: violationsCreated=1"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected violationsCreated=1, got $v_created"
    echo "Body: $proc_body"
    FAIL=$((FAIL + 1))
fi

# ── Test 3: Scheduled check status updated to no_response ────────────────────
echo ""
echo "--- Test 3: Expired check status updated to 'no_response' ---"
check_status=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM scheduled_checks WHERE id='$EXPIRED_CHECK';" \
    | tr -d ' \n')
check_val "Check status after process" "$check_status" "no_response"

# ── Test 4: Violation record created in DB ────────────────────────────────────
echo ""
echo "--- Test 4: Violation record persisted in violations table ---"
viol_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM violations WHERE scheduled_check_id='$EXPIRED_CHECK' AND violation_type='no_response';" \
    | tr -d ' \n')
check_val "violations row count" "$viol_count" "1"

# ── Test 5: Violation has correct fields ─────────────────────────────────────
echo ""
echo "--- Test 5: Violation has correct tenant_id, employee_id, site_id ---"
viol_fields=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT tenant_id || '|' || employee_id || '|' || site_id
     FROM violations WHERE scheduled_check_id='$EXPIRED_CHECK';" \
    | tr -d ' \n')
expected_fields="${TENANT_ID}|${EMP_ID}|${SITE_ID}"
check_val "Violation fields" "$viol_fields" "$expected_fields"

# ── Test 6: Violation is unresolved by default ────────────────────────────────
echo ""
echo "--- Test 6: Violation resolved=false by default ---"
resolved=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT resolved FROM violations WHERE scheduled_check_id='$EXPIRED_CHECK';" \
    | tr -d ' \n')
check_val "Violation resolved=false" "$resolved" "f"

# ── Test 7: Re-running process-expired is idempotent ─────────────────────────
echo ""
echo "--- Test 7: Re-running process-expired is idempotent (no duplicate violations) ---"
proc2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$PROCESS_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
proc2_body=$(echo "$proc2_resp" | head -n -1)
v2=$(echo "$proc2_body" | grep -o '"violationsCreated":[0-9]*' | cut -d: -f2)
viol_total=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM violations WHERE scheduled_check_id='$EXPIRED_CHECK';" \
    | tr -d ' \n')
if [ "$v2" -eq 0 ] && [ "$viol_total" -eq 1 ]; then
    echo "PASS: Re-run creates 0 new violations, total stays at 1"
    PASS=$((PASS + 1))
else
    echo "FAIL: v2=$v2 viol_total=$viol_total (expected 0 and 1)"
    FAIL=$((FAIL + 1))
fi

# ── Test 8: 'pending' checks are NOT processed (not yet dispatched) ───────────
echo ""
echo "--- Test 8: pending checks (not dispatched) are NOT turned into violations ---"
PENDING_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "WITH next_idx AS (
       SELECT COALESCE(MAX(check_index), 0) + 1 AS idx
       FROM scheduled_checks WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY'
     ),
     ins AS (
       INSERT INTO scheduled_checks
         (id, tenant_id, assignment_id, employee_id, site_id, shift_id, config_id,
          config_snapshot, check_date, check_index, scheduled_at, expires_at, status, created_at, updated_at)
       SELECT gen_random_uuid(), '$TENANT_ID', '$ASGN_ID', '$EMP_ID', '$SITE_ID', '$SHIFT_ID',
         (SELECT id FROM random_check_configs WHERE tenant_id='$TENANT_ID' AND site_id IS NULL LIMIT 1),
         '{\"checkMode\":\"location_only\"}'::jsonb, '$TODAY', idx,
         now() - interval '10 minutes', now() - interval '1 second',
         'pending', now(), now()
       FROM next_idx
       RETURNING id
     ) SELECT id FROM ins;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$PROCESS_URL" -H "Authorization: Bearer $ADMIN_TOKEN"
pending_status=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM scheduled_checks WHERE id='$PENDING_ID';" \
    | tr -d ' \n')
pending_viol=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM violations WHERE scheduled_check_id='$PENDING_ID';" \
    | tr -d ' \n')
if [ "$pending_status" = "pending" ] && [ "$pending_viol" -eq 0 ]; then
    echo "PASS: pending check unaffected (status=pending, violations=0)"
    PASS=$((PASS + 1))
else
    echo "FAIL: pending_status=$pending_status pending_viol=$pending_viol"
    FAIL=$((FAIL + 1))
fi

# ── Test 9: 'responded' checks are NOT processed ──────────────────────────────
echo ""
echo "--- Test 9: 'responded' checks are NOT processed ---"
RESPONDED_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "WITH next_idx AS (
       SELECT COALESCE(MAX(check_index), 0) + 1 AS idx
       FROM scheduled_checks WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY'
     ),
     ins AS (
       INSERT INTO scheduled_checks
         (id, tenant_id, assignment_id, employee_id, site_id, shift_id, config_id,
          config_snapshot, check_date, check_index, scheduled_at, expires_at, status, created_at, updated_at)
       SELECT gen_random_uuid(), '$TENANT_ID', '$ASGN_ID', '$EMP_ID', '$SITE_ID', '$SHIFT_ID',
         (SELECT id FROM random_check_configs WHERE tenant_id='$TENANT_ID' AND site_id IS NULL LIMIT 1),
         '{\"checkMode\":\"location_only\"}'::jsonb, '$TODAY', idx,
         now() - interval '10 minutes', now() - interval '1 second',
         'responded', now(), now()
       FROM next_idx
       RETURNING id
     ) SELECT id FROM ins;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$PROCESS_URL" -H "Authorization: Bearer $ADMIN_TOKEN"
responded_viol=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM violations WHERE scheduled_check_id='$RESPONDED_ID';" \
    | tr -d ' \n')
check_val "responded check creates 0 violations" "$responded_viol" "0"

# ── Test 10: Multiple expired checks → multiple violations ────────────────────
echo ""
echo "--- Test 10: Multiple expired sent checks → one violation each ---"
EXP2=$(insert_expired_check)
EXP3=$(insert_expired_check)
proc3_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$PROCESS_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
proc3_body=$(echo "$proc3_resp" | head -n -1)
v3=$(echo "$proc3_body" | grep -o '"violationsCreated":[0-9]*' | cut -d: -f2)
if [ "$v3" -eq 2 ]; then
    echo "PASS: 2 expired checks → violationsCreated=2"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected violationsCreated=2, got $v3"
    FAIL=$((FAIL + 1))
fi

# ── Test 11: process-expired requires auth ────────────────────────────────────
echo ""
echo "--- Test 11: process-expired without token returns 401 ---"
run_test "No token returns 401" 401 \
    -X POST "$PROCESS_URL"

# ── Test 12: process-expired requires randomchecks:configure permission ───────
echo ""
echo "--- Test 12: Employee without permission gets 403 ---"
UNAUTH_EMAIL="noresp.unauth.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$UNAUTH_EMAIL\",\"firstName\":\"U\",\"lastName\":\"User\"}"
UINV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$UNAUTH_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$UINV_TOKEN\",\"password\":\"Employee@1234\"}"
ulogin=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$UNAUTH_EMAIL\",\"password\":\"Employee@1234\"}")
USER_TOKEN=$(echo "$ulogin" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "No permission returns 403" 403 \
    -X POST "$PROCESS_URL" \
    -H "Authorization: Bearer $USER_TOKEN"

echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
