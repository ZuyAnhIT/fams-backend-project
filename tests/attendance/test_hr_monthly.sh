#!/usr/bin/env bash
# Tests for task 86 — HR monthly attendance aggregate
# GET /api/v1/tenants/{tenantId}/attendance/monthly?year=YYYY&month=M
# Usage: BASE_URL=http://localhost:8080 bash test_hr_monthly.sh

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

echo "=== HR Monthly Attendance Tests (task 86) ==="
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
    -d "{\"name\":\"HR Corp ${TS}\",\"slug\":\"hr-corp-${TS}\"}")
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

# Create employee 1
INVITE_EMAIL1="hr.emp1.${TS}@example.com"
inv1_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL1\",\"firstName\":\"HR1\",\"lastName\":\"Tester\"}")
if [ "$(echo "$inv1_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation 1"; exit 1; fi
INV_TOKEN1=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL1' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN1\",\"password\":\"Employee@1234\"}"
emp1_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$INVITE_EMAIL1\",\"password\":\"Employee@1234\"}")
EMP1_TOKEN=$(echo "$emp1_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
EMP1_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$INVITE_EMAIL1' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP1_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"

# Create employee 2
INVITE_EMAIL2="hr.emp2.${TS}@example.com"
inv2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL2\",\"firstName\":\"HR2\",\"lastName\":\"Tester\"}")
if [ "$(echo "$inv2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation 2"; exit 1; fi
INV_TOKEN2=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL2' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN2\",\"password\":\"Employee@1234\"}"
emp2_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$INVITE_EMAIL2\",\"password\":\"Employee@1234\"}")
EMP2_TOKEN=$(echo "$emp2_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
EMP2_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$INVITE_EMAIL2' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP2_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"

THIS_YEAR=$(date -u +%Y)
THIS_MONTH=$(date -u +%-m)

echo "Setup complete. TENANT_ID=$TENANT_ID EMP1=$EMP1_ID EMP2=$EMP2_ID"
echo ""

ATT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/attendance"
MONTHLY_URL="$ATT_URL/monthly?year=$THIS_YEAR&month=$THIS_MONTH"

# ── Test 1: /monthly → 200 with required fields ────────────────────────────────
echo "--- Test 1: GET /monthly → 200 with pagination + aggregate fields ---"
empty_resp=$(curl -s -w "\n%{http_code}" "$MONTHLY_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
empty_body=$(echo "$empty_resp" | head -n -1)
empty_status=$(echo "$empty_resp" | tail -n 1)
if [ "$empty_status" -eq 200 ]; then
    for field in content totalElements totalPages page size; do
        echo "$empty_body" | grep -q "\"$field\"" && echo "  field '$field' present" || { echo "FAIL: Missing field '$field'"; FAIL=$((FAIL+1)); }
    done
    echo "PASS: GET /monthly returns 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $empty_status — $empty_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: No check-ins → totalElements=0 ────────────────────────────────────
echo "--- Test 2: No check-ins yet → totalElements=0 ---"
total=$(echo "$empty_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
if [ "${total:-1}" -eq 0 ]; then
    echo "PASS: totalElements=0 before any check-ins"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected totalElements=0, got $total"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Check-in + checkout for both employees ─────────────────────────────────────
ci1_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP1_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"hr-dev1\"}")
CI1_ID=$(echo "$ci1_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
sleep 2
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CI1_ID/checkout" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP1_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542}'

ci2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"hr-dev2\"}")
CI2_ID=$(echo "$ci2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
sleep 2
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CI2_ID/checkout" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542}'

# ── Test 3: After 2 employees check in → totalElements=2 ─────────────────────
echo "--- Test 3: After 2 employees check in → totalElements=2 ---"
after_resp=$(curl -s -w "\n%{http_code}" "$MONTHLY_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
after_body=$(echo "$after_resp" | head -n -1)
after_status=$(echo "$after_resp" | tail -n 1)
if [ "$after_status" -eq 200 ]; then
    total=$(echo "$after_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "${total:-0}" -ge 2 ]; then
        echo "PASS: totalElements=$total (>= 2 employees)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected totalElements>=2, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $after_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: aggregate fields present in content items ────────────────────────
echo "--- Test 4: Content items have aggregate fields ---"
if [ "$after_status" -eq 200 ]; then
    for field in presentDays totalWorkMinutes lateDays totalLateMinutes \
                 earlyLeaveDays totalEarlyLeaveMinutes totalOtMinutes missingCheckoutDays; do
        if echo "$after_body" | grep -q "\"$field\""; then
            echo "  field '$field' present"
        else
            echo "FAIL: Missing field '$field' in content"
            FAIL=$((FAIL + 1))
        fi
    done
    echo "PASS: All aggregate fields present"
    PASS=$((PASS + 1))
else
    echo "SKIP: previous request failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Filter by employeeId returns exactly 1 record ─────────────────────
echo "--- Test 5: Filter ?employeeId= returns 1 record ---"
filtered_resp=$(curl -s -w "\n%{http_code}" "$MONTHLY_URL&employeeId=$EMP1_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
filtered_body=$(echo "$filtered_resp" | head -n -1)
filtered_status=$(echo "$filtered_resp" | tail -n 1)
if [ "$filtered_status" -eq 200 ]; then
    ftotal=$(echo "$filtered_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    femp=$(echo "$filtered_body" | grep -o '"employeeId":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ "${ftotal:-0}" -eq 1 ] && [ "$femp" = "$EMP1_ID" ]; then
        echo "PASS: Filtered to 1 record for EMP1"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected totalElements=1 and employeeId=$EMP1_ID, got total=$ftotal emp=$femp"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $filtered_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Filter by siteId returns correct records ─────────────────────────
echo "--- Test 6: Filter ?siteId= returns records for that site ---"
site_resp=$(curl -s -w "\n%{http_code}" "$MONTHLY_URL&siteId=$SITE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
site_body=$(echo "$site_resp" | head -n -1)
site_status=$(echo "$site_resp" | tail -n 1)
if [ "$site_status" -eq 200 ]; then
    stotal=$(echo "$site_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    sid=$(echo "$site_body" | grep -o '"siteId":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ "${stotal:-0}" -ge 1 ] && [ "$sid" = "$SITE_ID" ]; then
        echo "PASS: Site filter: totalElements=$stotal siteId=$sid"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected >=1 records for siteId=$SITE_ID, got total=$stotal sid=$sid"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $site_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: /monthly → 401 without token ─────────────────────────────────────
echo "--- Test 7: /monthly without token → 401 ---"
run_test "Unauthenticated" 401 -s "$MONTHLY_URL"
echo ""

# ── Test 8: /monthly → 403 for employee ──────────────────────────────────────
echo "--- Test 8: /monthly with employee token → 403 ---"
run_test "Employee forbidden" 403 -s "$MONTHLY_URL" \
    -H "Authorization: Bearer $EMP1_TOKEN"
echo ""

# ── Test 9: invalid month → 400 ──────────────────────────────────────────────
echo "--- Test 9: month=0 → 400 ---"
run_test "Invalid month=0" 400 -s \
    "$ATT_URL/monthly?year=$THIS_YEAR&month=0" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 10: year/month in content match request ──────────────────────────────
echo "--- Test 10: year and month in response match request params ---"
yr_val=$(echo "$after_body" | grep -o '"year":[0-9]*' | head -1 | cut -d: -f2)
mo_val=$(echo "$after_body" | grep -o '"month":[0-9]*' | head -1 | cut -d: -f2)
if [ "$yr_val" = "$THIS_YEAR" ] && [ "$mo_val" = "$THIS_MONTH" ]; then
    echo "PASS: year=$yr_val month=$mo_val"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected year=$THIS_YEAR month=$THIS_MONTH, got year=$yr_val month=$mo_val"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
