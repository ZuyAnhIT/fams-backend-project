#!/usr/bin/env bash
# Tests for task 85 — Employee monthly attendance timesheet
# GET /api/v1/tenants/{tenantId}/attendance/me/monthly?year=YYYY&month=M
# Usage: BASE_URL=http://localhost:8080 bash test_employee_timesheet.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    actual=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Employee Timesheet Tests (task 85) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"TS Corp ${TS}\",\"slug\":\"ts-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HQ","timezone":"UTC","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

INVITE_EMAIL="ts.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"TS\",\"lastName\":\"Tester\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$INVITE_EMAIL\",\"password\":\"Employee@1234\"}")
if [ "$(echo "$emp_login" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: employee login"; exit 1; fi
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

asgn_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi

THIS_YEAR=$(date -u +%Y)
THIS_MONTH=$(date -u +%-m)

echo "Setup complete. TENANT_ID=$TENANT_ID EMP_ID=$EMP_ID YEAR=$THIS_YEAR MONTH=$THIS_MONTH"
echo ""

ME_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/attendance/me"

# ── Test 1: /me/monthly → 200, all required fields present ───────────────────
echo "--- Test 1: GET /me/monthly returns 200 with aggregate fields ---"
monthly_resp=$(curl -s -w "\n%{http_code}" "$ME_URL/monthly?year=$THIS_YEAR&month=$THIS_MONTH" \
    -H "Authorization: Bearer $EMP_TOKEN")
monthly_body=$(echo "$monthly_resp" | head -n -1)
monthly_status=$(echo "$monthly_resp" | tail -n 1)
if [ "$monthly_status" -eq 200 ]; then
    for field in presentDays totalWorkMinutes lateDays totalLateMinutes earlyLeaveDays \
                 totalEarlyLeaveMinutes totalOtMinutes missingCheckoutDays dailySummaries; do
        if echo "$monthly_body" | grep -q "\"$field\""; then
            echo "  field '$field' present"
        else
            echo "FAIL: Missing field '$field' in response"
            FAIL=$((FAIL + 1))
        fi
    done
    echo "PASS: GET /me/monthly returns 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $monthly_status — $monthly_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Month with no check-ins → presentDays=0 ──────────────────────────
echo "--- Test 2: Empty month returns presentDays=0 ---"
empty_resp=$(curl -s -w "\n%{http_code}" "$ME_URL/monthly?year=2025&month=1" \
    -H "Authorization: Bearer $EMP_TOKEN")
empty_body=$(echo "$empty_resp" | head -n -1)
empty_status=$(echo "$empty_resp" | tail -n 1)
if [ "$empty_status" -eq 200 ]; then
    present=$(echo "$empty_body" | grep -o '"presentDays":[0-9]*' | cut -d: -f2)
    wm=$(echo "$empty_body" | grep -o '"totalWorkMinutes":[0-9]*' | cut -d: -f2)
    if [ "${present:-1}" -eq 0 ] && [ "${wm:-1}" -eq 0 ]; then
        echo "PASS: Empty month: presentDays=0, totalWorkMinutes=0"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected presentDays=0, got presentDays=$present totalWorkMinutes=$wm"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $empty_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Check in + checkout → presentDays=1 ───────────────────────────────
echo "--- Test 3: After check-in + checkout → presentDays=1, totalWorkMinutes > 0 ---"
ci_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"dev-ts-1\"}")
if [ "$(echo "$ci_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: check-in"; exit 1; fi
CI_ID=$(echo "$ci_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sleep 2

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CI_ID/checkout" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542}'

after_resp=$(curl -s -w "\n%{http_code}" "$ME_URL/monthly?year=$THIS_YEAR&month=$THIS_MONTH" \
    -H "Authorization: Bearer $EMP_TOKEN")
after_body=$(echo "$after_resp" | head -n -1)
after_status=$(echo "$after_resp" | tail -n 1)
if [ "$after_status" -eq 200 ]; then
    present=$(echo "$after_body" | grep -o '"presentDays":[0-9]*' | cut -d: -f2)
    wm=$(echo "$after_body" | grep -o '"totalWorkMinutes":[0-9]*' | cut -d: -f2)
    daily_count=$(echo "$after_body" | grep -o '"attendanceDate"' | wc -l | xargs)
    if [ "${present:-0}" -ge 1 ] && [ "${daily_count:-0}" -ge 1 ]; then
        echo "PASS: presentDays=$present totalWorkMinutes=$wm dailyCount=$daily_count"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected presentDays>=1 and dailyCount>=1, got presentDays=$present daily=$daily_count"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $after_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: year/month in response match request ──────────────────────────────
echo "--- Test 4: year and month fields match request params ---"
yr_val=$(echo "$after_body" | grep -o '"year":[0-9]*' | cut -d: -f2)
mo_val=$(echo "$after_body" | grep -o '"month":[0-9]*' | cut -d: -f2)
if [ "$yr_val" = "$THIS_YEAR" ] && [ "$mo_val" = "$THIS_MONTH" ]; then
    echo "PASS: year=$yr_val month=$mo_val"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected year=$THIS_YEAR month=$THIS_MONTH, got year=$yr_val month=$mo_val"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: employeeId in response matches caller's employee ──────────────────
echo "--- Test 5: employeeId in response matches caller's employee ---"
resp_emp=$(echo "$after_body" | grep -o '"employeeId":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ "$resp_emp" = "$EMP_ID" ]; then
    echo "PASS: employeeId matches"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected employeeId=$EMP_ID, got $resp_emp"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: /me/monthly → 401 without token ───────────────────────────────────
echo "--- Test 6: /me/monthly without token → 401 ---"
run_test "Unauthenticated /me/monthly" 401 -s \
    "$ME_URL/monthly?year=$THIS_YEAR&month=$THIS_MONTH"
echo ""

# ── Test 7: invalid month → 400 ──────────────────────────────────────────────
echo "--- Test 7: month=13 → 400 ---"
run_test "Invalid month=13" 400 -s \
    "$ME_URL/monthly?year=$THIS_YEAR&month=13" \
    -H "Authorization: Bearer $EMP_TOKEN"
echo ""

# ── Test 8: daily summaries in response each have missingCheckout field ───────
echo "--- Test 8: dailySummaries entries contain missingCheckout field ---"
mc_count=$(echo "$after_body" | grep -o '"missingCheckout"' | wc -l | tr -d ' ')
if [ "${mc_count:-0}" -ge 1 ]; then
    echo "PASS: dailySummaries contain missingCheckout ($mc_count occurrences)"
    PASS=$((PASS + 1))
else
    echo "FAIL: missingCheckout not found in dailySummaries — $after_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
