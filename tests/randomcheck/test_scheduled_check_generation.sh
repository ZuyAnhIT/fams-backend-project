#!/usr/bin/env bash
# Tests for scheduled check generation (task 96)
# POST /api/v1/tenants/{tenantId}/scheduled-checks/generate
# GET  /api/v1/tenants/{tenantId}/scheduled-checks
# Usage: BASE_URL=http://localhost:8080 bash test_scheduled_check_generation.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    local actual_status
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Scheduled Check Generation Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: admin login ────────────────────────────────────────────────────────
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."

# ── Setup: tenant ─────────────────────────────────────────────────────────────
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"SchedCheck Corp ${TS}\",\"slug\":\"schedcheck-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant: $TENANT_ID"

# ── Setup: site ───────────────────────────────────────────────────────────────
s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Test Site ${TS}\",\"code\":\"TS-${TS}\",\"address\":\"123 St\",\"timezone\":\"Asia/Ho_Chi_Minh\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site ($(echo "$s_resp" | tail -n 1))"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Site: $SITE_ID"

# ── Setup: shift ──────────────────────────────────────────────────────────────
sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Morning","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Shift: $SHIFT_ID"

# ── Setup: employee via invitation ────────────────────────────────────────────
INVITE_EMAIL="sc.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"SC\",\"lastName\":\"Emp\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
if [ -z "$INV_TOKEN" ]; then echo "SETUP FAILED: invitation token from DB"; exit 1; fi

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
if [ -z "$EMP_ID" ]; then echo "SETUP FAILED: employee id from DB"; exit 1; fi
echo "Employee: $EMP_ID"

# ── Setup: assignment covering today ──────────────────────────────────────────
TODAY=$(date +%Y-%m-%d)
asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi
ASGN_ID=$(echo "$asgn_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Assignment: $ASGN_ID"

# ── Setup: random check config (2 checks per shift) ───────────────────────────
cfg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 2,
      "minIntervalMinutes": 60,
      "allowedStartTime": "09:00:00",
      "allowedEndTime": "16:00:00",
      "checkMode": "location_only",
      "applicableRoles": ["worker"],
      "responseWindowSeconds": 300
    }')
if [ "$(echo "$cfg_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: config"; exit 1; fi
CFG_ID=$(echo "$cfg_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Config: $CFG_ID"
echo ""

GENERATE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/generate"
LIST_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks"

# ── Tests ─────────────────────────────────────────────────────────────────────
echo "--- Test 1: Trigger generation for today — should return 200 ---"
gen_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$GENERATE_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"date\":\"$TODAY\"}")
gen_status=$(echo "$gen_resp" | tail -n 1)
gen_body=$(echo "$gen_resp" | head -n -1)
if [ "$gen_status" -eq 200 ]; then
    echo "PASS: Generation trigger returns 200"
    PASS=$((PASS + 1))
    created=$(echo "$gen_body" | grep -o '"created":[0-9]*' | cut -d: -f2)
    echo "  Checks created: $created"
else
    echo "FAIL: Generation trigger — expected 200, got $gen_status"
    echo "Body: $gen_body"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 2: Generated 2 checks (checksPerShift=2) ---"
check_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM scheduled_checks WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY' AND deleted_at IS NULL;" \
    | tr -d ' \n')
if [ "$check_count" -eq 2 ]; then
    echo "PASS: 2 scheduled checks in DB for assignment on $TODAY"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 2 scheduled checks, found $check_count"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 3: Checks have correct check_index (1 and 2) ---"
idx_ok=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM scheduled_checks WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY' AND check_index IN (1,2) AND deleted_at IS NULL;" \
    | tr -d ' \n')
if [ "$idx_ok" -eq 2 ]; then
    echo "PASS: Both check_index values (1, 2) are present"
    PASS=$((PASS + 1))
else
    echo "FAIL: check_index values incorrect — $idx_ok rows with index 1 or 2"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 4: scheduled_at is within allowed window (09:00–16:00 Asia/Ho_Chi_Minh) ---"
window_ok=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM scheduled_checks
     WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY' AND deleted_at IS NULL
     AND (scheduled_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::time BETWEEN '09:00' AND '16:00';" \
    | tr -d ' \n')
if [ "$window_ok" -eq 2 ]; then
    echo "PASS: Both checks fall within allowed time window"
    PASS=$((PASS + 1))
else
    echo "FAIL: $window_ok of 2 checks are within allowed window"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 5: expires_at = scheduled_at + 300 seconds ---"
expires_ok=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM scheduled_checks
     WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY' AND deleted_at IS NULL
     AND EXTRACT(EPOCH FROM (expires_at - scheduled_at)) = 300;" \
    | tr -d ' \n')
if [ "$expires_ok" -eq 2 ]; then
    echo "PASS: Both checks have expires_at = scheduled_at + 300s"
    PASS=$((PASS + 1))
else
    echo "FAIL: $expires_ok of 2 checks have correct expires_at"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 6: min_interval_minutes respected (≥ 60 min between checks) ---"
interval_ok=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT CASE WHEN MIN(gap_minutes) >= 60 THEN 1 ELSE 0 END
     FROM (
       SELECT EXTRACT(EPOCH FROM (lead(scheduled_at) OVER (ORDER BY scheduled_at) - scheduled_at)) / 60 AS gap_minutes
       FROM scheduled_checks
       WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY' AND deleted_at IS NULL
     ) gaps WHERE gap_minutes IS NOT NULL;" \
    | tr -d ' \n')
if [ "$interval_ok" = "1" ]; then
    echo "PASS: Minimum interval of 60 min between checks is respected"
    PASS=$((PASS + 1))
else
    echo "FAIL: Minimum interval not respected (got $interval_ok)"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 7: All checks start with status 'pending' ---"
pending_ok=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM scheduled_checks WHERE assignment_id='$ASGN_ID' AND check_date='$TODAY' AND status='pending' AND deleted_at IS NULL;" \
    | tr -d ' \n')
if [ "$pending_ok" -eq 2 ]; then
    echo "PASS: All checks have status 'pending'"
    PASS=$((PASS + 1))
else
    echo "FAIL: $pending_ok of 2 checks have status 'pending'"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 8: List endpoint returns the generated checks ---"
list_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$LIST_URL?date=$TODAY" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_status=$(echo "$list_resp" | tail -n 1)
list_body=$(echo "$list_resp" | head -n -1)
list_count=$(echo "$list_body" | grep -o '"id":"[^"]*"' | wc -l | tr -d ' ')
if [ "$list_status" -eq 200 ] && [ "$list_count" -ge 2 ]; then
    echo "PASS: List returns $list_count check(s) for today (HTTP 200)"
    PASS=$((PASS + 1))
else
    echo "FAIL: List returned HTTP $list_status with $list_count items"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 9: Generation is idempotent — re-running returns created=0 ---"
gen2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$GENERATE_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"date\":\"$TODAY\"}")
gen2_body=$(echo "$gen2_resp" | head -n -1)
created2=$(echo "$gen2_body" | grep -o '"created":[0-9]*' | cut -d: -f2)
if [ "$created2" -eq 0 ]; then
    echo "PASS: Re-run returns created=0 (idempotent)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Re-run created $created2 checks (should be 0)"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 10: Unauthorized — should return 401 ---"
run_test "No auth token returns 401" 401 \
    -X POST "$GENERATE_URL" \
    -H "Content-Type: application/json" \
    -d "{\"date\":\"$TODAY\"}"

echo ""
echo "--- Test 11: Employee with non-matching role is skipped ---"
# Create 2nd employee with role=supervisor and config only applies to worker
INVITE2="sc.sup.${TS}@example.com"
inv2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE2\",\"firstName\":\"Sup\",\"lastName\":\"User\"}")
INV2_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE2' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV2_TOKEN\",\"password\":\"Employee@1234\"}"
EMP2_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$INVITE2' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
# Assign as supervisor
asgn2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP2_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"supervisor\"}")
ASGN2_ID=$(echo "$asgn2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

TOMORROW=$(date -d "+1 day" +%Y-%m-%d 2>/dev/null || date -v+1d +%Y-%m-%d)
gen3_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$GENERATE_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"date\":\"$TOMORROW\"}")
# Worker gets 2 checks, supervisor gets 0 (role not in applicable_roles)
worker_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM scheduled_checks WHERE assignment_id='$ASGN_ID' AND check_date='$TOMORROW' AND deleted_at IS NULL;" \
    | tr -d ' \n')
super_count=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM scheduled_checks WHERE assignment_id='$ASGN2_ID' AND check_date='$TOMORROW' AND deleted_at IS NULL;" \
    | tr -d ' \n')
if [ "$worker_count" -eq 2 ] && [ "$super_count" -eq 0 ]; then
    echo "PASS: Worker gets 2 checks, supervisor gets 0 (role filter works)"
    PASS=$((PASS + 1))
else
    echo "FAIL: worker_count=$worker_count super_count=$super_count (expected 2 and 0)"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "--- Test 12: No config → no checks generated ---"
# Create a fresh tenant with no config
t2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"NoConfig ${TS}\",\"slug\":\"no-config-${TS}\"}")
T2_ID=$(echo "$t2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
gen4_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$T2_ID/scheduled-checks/generate" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"date\":\"$TODAY\"}")
gen4_body=$(echo "$gen4_resp" | head -n -1)
created4=$(echo "$gen4_body" | grep -o '"created":[0-9]*' | cut -d: -f2)
if [ "${created4:-0}" -eq 0 ]; then
    echo "PASS: No config → 0 checks generated"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 0 checks with no config, got $created4"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
