#!/usr/bin/env bash
# Tests for HR manual random check trigger (task 108)
# POST /api/v1/tenants/{tenantId}/scheduled-checks/manual
# Usage: BASE_URL=http://localhost:8080 bash test_manual_check.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

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

echo "=== Manual Check Trigger Tests (task 108) ==="
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

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Manual Corp ${TS}\",\"slug\":\"manual-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Manual Site\",\"code\":\"MS-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_EMAIL="manual.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"Manual\",\"lastName\":\"Emp\"}"
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

MANUAL_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/manual"
echo "Setup complete. TENANT=$TENANT_ID EMP=$EMP_ID SITE=$SITE_ID"
echo ""

# ── Test 1: Manual trigger returns 201 ────────────────────────────────────────
echo "--- Test 1: Manual trigger returns 201 ---"
trig_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$MANUAL_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"employeeId\":\"$EMP_ID\"}")
trig_status=$(echo "$trig_resp" | tail -n 1)
trig_body=$(echo "$trig_resp" | head -n -1)
if [ "$trig_status" -eq 201 ]; then
    echo "PASS: Manual trigger returns 201"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 201, got $trig_status"
    echo "Body: $trig_body"
    FAIL=$((FAIL + 1))
fi

# Extract check ID from response
CHECK_ID=$(echo "$trig_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Created check: $CHECK_ID"

# ── Test 2: Check created with status='sent' ───────────────────────────────────
echo ""
echo "--- Test 2: Check is immediately status='sent' ---"
db_status=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM scheduled_checks WHERE id='$CHECK_ID';" | tr -d ' \n')
check_val "Check status=sent" "$db_status" "sent"

# ── Test 3: scheduled_at is approximately now ──────────────────────────────────
echo ""
echo "--- Test 3: scheduled_at is within last 5 seconds ---"
secs_ago=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT EXTRACT(EPOCH FROM (now() - scheduled_at))::int FROM scheduled_checks WHERE id='$CHECK_ID';" \
    | tr -d ' \n')
if [ "${secs_ago:-99}" -le 5 ]; then
    echo "PASS: scheduled_at is ${secs_ago}s ago (within 5s)"
    PASS=$((PASS + 1))
else
    echo "FAIL: scheduled_at is ${secs_ago}s ago (expected <=5)"
    FAIL=$((FAIL + 1))
fi

# ── Test 4: expires_at = scheduled_at + responseWindowSeconds (300) ───────────
echo ""
echo "--- Test 4: expires_at = scheduled_at + 300s ---"
window_ok=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT CASE WHEN ABS(EXTRACT(EPOCH FROM (expires_at - scheduled_at)) - 300) < 2 THEN '1' ELSE '0' END
     FROM scheduled_checks WHERE id='$CHECK_ID';" | tr -d ' \n')
check_val "expires_at = scheduled_at + 300s" "$window_ok" "1"

# ── Test 5: Response body contains employeeId and status ──────────────────────
echo ""
echo "--- Test 5: Response body contains correct employeeId and status='sent' ---"
resp_emp=$(echo "$trig_body" | grep -o '"employeeId":"[^"]*"' | cut -d'"' -f4)
resp_status=$(echo "$trig_body" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
if [ "$resp_emp" = "$EMP_ID" ] && [ "$resp_status" = "sent" ]; then
    echo "PASS: Response has correct employeeId and status=sent"
    PASS=$((PASS + 1))
else
    echo "FAIL: resp_emp=$resp_emp resp_status=$resp_status"
    FAIL=$((FAIL + 1))
fi

# ── Test 6: check_index is non-positive (manual sentinel) ─────────────────────
echo ""
echo "--- Test 6: check_index is <= 0 (manual check sentinel) ---"
check_idx=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT check_index FROM scheduled_checks WHERE id='$CHECK_ID';" | tr -d ' \n')
if [ "$check_idx" -le 0 ]; then
    echo "PASS: check_index=$check_idx (non-positive, manual check)"
    PASS=$((PASS + 1))
else
    echo "FAIL: check_index=$check_idx (expected <= 0)"
    FAIL=$((FAIL + 1))
fi

# ── Test 7: Second manual trigger same day gets unique check_index ─────────────
echo ""
echo "--- Test 7: Second manual trigger on same day gets a distinct check_index ---"
trig2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$MANUAL_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"employeeId\":\"$EMP_ID\"}")
trig2_status=$(echo "$trig2_resp" | tail -n 1)
CHECK_ID2=$(echo "$trig2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
check_idx2=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT check_index FROM scheduled_checks WHERE id='$CHECK_ID2';" | tr -d ' \n')
if [ "$trig2_status" -eq 201 ] && [ "$check_idx2" -ne "$check_idx" ]; then
    echo "PASS: Second check created (HTTP 201), check_index=$check_idx2 != $check_idx"
    PASS=$((PASS + 1))
else
    echo "FAIL: trig2_status=$trig2_status check_idx2=$check_idx2 check_idx=$check_idx"
    FAIL=$((FAIL + 1))
fi

# ── Test 8: checkMode override is accepted ─────────────────────────────────────
echo ""
echo "--- Test 8: checkMode override in request is reflected in config_snapshot ---"
trig3_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$MANUAL_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"employeeId\":\"$EMP_ID\",\"checkMode\":\"location_face\"}")
trig3_status=$(echo "$trig3_resp" | tail -n 1)
CHECK_ID3=$(echo "$trig3_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
snapshot_mode=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT config_snapshot->>'checkMode' FROM scheduled_checks WHERE id='$CHECK_ID3';" \
    | tr -d ' \n')
if [ "$trig3_status" -eq 201 ] && [ "$snapshot_mode" = "location_face" ]; then
    echo "PASS: checkMode override stored correctly (=$snapshot_mode)"
    PASS=$((PASS + 1))
else
    echo "FAIL: trig3_status=$trig3_status snapshot_mode=$snapshot_mode"
    FAIL=$((FAIL + 1))
fi

# ── Test 9: Invalid checkMode returns 400 ─────────────────────────────────────
echo ""
echo "--- Test 9: Invalid checkMode returns 400 ---"
run_test "Invalid checkMode returns 400" 400 \
    -X POST "$MANUAL_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"employeeId\":\"$EMP_ID\",\"checkMode\":\"invalid_mode\"}"

# ── Test 10: Employee not assigned to site returns 400 ────────────────────────
echo ""
echo "--- Test 10: Employee without assignment at site returns 400 ---"
# Create a second site with no assignment for this employee
s2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Other Site\",\"code\":\"OS-${TS}\",\"address\":\"2 St\",\"timezone\":\"UTC\"}")
SITE2_ID=$(echo "$s2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "Employee not assigned to site returns 400" 400 \
    -X POST "$MANUAL_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$SITE2_ID\",\"employeeId\":\"$EMP_ID\"}"

# ── Test 11: Missing config returns 400 ────────────────────────────────────────
echo ""
echo "--- Test 11: No config for tenant returns 400 ---"
# Create a second tenant with a site+employee+assignment but NO config
t2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"NoCfg Corp ${TS}\",\"slug\":\"nocfg-${TS}\"}")
T2_ID=$(echo "$t2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
s2nc_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$T2_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"NC Site\",\"code\":\"NC-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
SITE_NC=$(echo "$s2nc_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
sh2nc_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$T2_ID/sites/$SITE_NC/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
SHIFT_NC=$(echo "$sh2nc_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
EMP_NC_EMAIL="nocfg.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$T2_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_NC_EMAIL\",\"firstName\":\"NC\",\"lastName\":\"Emp\"}"
INV_NC=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP_NC_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_NC\",\"password\":\"Employee@1234\"}"
EMP_NC=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$EMP_NC_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$T2_ID/sites/$SITE_NC/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_NC\",\"shiftId\":\"$SHIFT_NC\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"
run_test "No config returns 400" 400 \
    -X POST "$BASE_URL/api/v1/tenants/$T2_ID/scheduled-checks/manual" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$SITE_NC\",\"employeeId\":\"$EMP_NC\"}"

# ── Test 12: Non-existent site returns 404 ─────────────────────────────────────
echo ""
echo "--- Test 12: Non-existent site returns 404 ---"
FAKE_SITE="00000000-0000-0000-0000-000000000001"
run_test "Non-existent site returns 404" 404 \
    -X POST "$MANUAL_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$FAKE_SITE\",\"employeeId\":\"$EMP_ID\"}"

# ── Test 13: Missing siteId returns 400 ───────────────────────────────────────
echo ""
echo "--- Test 13: Missing siteId in request returns 400 ---"
run_test "Missing siteId returns 400" 400 \
    -X POST "$MANUAL_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\"}"

# ── Test 14: No auth returns 401 ──────────────────────────────────────────────
echo ""
echo "--- Test 14: No auth token returns 401 ---"
run_test "No token returns 401" 401 \
    -X POST "$MANUAL_URL" \
    -H "Content-Type: application/json" \
    -d "{\"siteId\":\"$SITE_ID\",\"employeeId\":\"$EMP_ID\"}"

# ── Test 15: Employee without randomchecks:configure gets 403 ─────────────────
echo ""
echo "--- Test 15: Employee without permission gets 403 ---"
UNAUTH_EMAIL="manual.unauth.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$UNAUTH_EMAIL\",\"firstName\":\"U\",\"lastName\":\"U\"}"
UINV=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$UNAUTH_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$UINV\",\"password\":\"Employee@1234\"}"
ulogin=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$UNAUTH_EMAIL\",\"password\":\"Employee@1234\"}")
USER_TOKEN=$(echo "$ulogin" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "No permission returns 403" 403 \
    -X POST "$MANUAL_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $USER_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"employeeId\":\"$EMP_ID\"}"

# ── Test 16: Employee can respond to manual check ─────────────────────────────
echo ""
echo "--- Test 16: Employee can respond to the manually triggered check ---"
EMP_LOGIN=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
EMP_TOKEN=$(echo "$EMP_LOGIN" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

respond_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/$CHECK_ID/respond" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":10.0,"longitude":106.0}')
respond_status=$(echo "$respond_resp" | tail -n 1)
final_status=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM scheduled_checks WHERE id='$CHECK_ID';" | tr -d ' \n')
if [ "$respond_status" -eq 200 ] && [ "$final_status" = "responded" ]; then
    echo "PASS: Employee responded to manual check successfully"
    PASS=$((PASS + 1))
else
    echo "FAIL: respond_status=$respond_status final_status=$final_status"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=============================="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo "=============================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
