#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/dashboard/employee
# Covers task 119 (Employee dashboard — today shift, check-in status, monthly attendance)
# Usage: BASE_URL=http://localhost:8080 bash test_employee_dashboard.sh

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

echo "=== Employee Dashboard Tests (task 119) ==="
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
    -d "{\"name\":\"Dashboard Corp ${TS}\",\"slug\":\"dash-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create a site
s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Main Site","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Invite employee
INVITE_EMAIL="dash.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Dashboard\",\"lastName\":\"Emp\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"password\":\"Employee@1234\"}")
if [ "$(echo "$emp_login" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: employee login"; exit 1; fi
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

# Assign employee to site (active today)
TODAY=$(date -u +%Y-%m-%d)
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"startDate\":\"$TODAY\",\"role\":\"worker\"}"

echo "Setup complete. TENANT_ID=$TENANT_ID  EMP_ID=$EMP_ID  SITE_ID=$SITE_ID"
echo ""

DASH_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/dashboard/employee"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -s "$DASH_URL"
echo ""

# ── Test 2: Employee with profile → 200 ──────────────────────────────────────
echo "--- Test 2: Employee gets dashboard → 200 ---"
dash_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $EMP_TOKEN" "$DASH_URL")
dash_body=$(echo "$dash_resp" | head -n -1)
dash_status=$(echo "$dash_resp" | tail -n 1)
if [ "$dash_status" -eq 200 ]; then
    echo "PASS: Dashboard returned HTTP 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $dash_status — $dash_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Response structure has required keys ──────────────────────────────
echo "--- Test 3: Response contains todayShifts, checkin, monthlyAttendance ---"
if [ "$dash_status" -eq 200 ]; then
    MISSING=""
    for field in '"todayShifts"' '"checkin"' '"monthlyAttendance"'; do
        if ! echo "$dash_body" | grep -q "$field"; then
            MISSING="$MISSING $field"
        fi
    done
    if [ -z "$MISSING" ]; then
        echo "PASS: All top-level fields present"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing:$MISSING — $dash_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: todayShifts contains the active assignment ────────────────────────
echo "--- Test 4: todayShifts lists the active assignment ---"
if [ "$dash_status" -eq 200 ]; then
    if echo "$dash_body" | grep -q "$SITE_ID"; then
        echo "PASS: Active site assignment present in todayShifts"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected siteId $SITE_ID in todayShifts — $dash_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: checkin is null before checking in ────────────────────────────────
echo "--- Test 5: checkin is null before first check-in ---"
if [ "$dash_status" -eq 200 ]; then
    if echo "$dash_body" | grep -q '"checkin":null'; then
        echo "PASS: checkin is null as expected"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected checkin:null — $dash_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: monthlyAttendance has month field ─────────────────────────────────
echo "--- Test 6: monthlyAttendance contains month field ---"
if [ "$dash_status" -eq 200 ]; then
    if echo "$dash_body" | grep -q '"month"'; then
        MONTH_VAL=$(echo "$dash_body" | grep -o '"month":"[^"]*"' | cut -d'"' -f4)
        echo "PASS: monthlyAttendance.month = $MONTH_VAL"
        PASS=$((PASS + 1))
    else
        echo "FAIL: No month field — $dash_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Check in, then dashboard shows open session ───────────────────────
echo "--- Test 7: After check-in dashboard shows open session ---"
ci_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}")
ci_status=$(echo "$ci_resp" | tail -n 1)
if [ "$ci_status" -eq 201 ]; then
    dash_resp2=$(curl -s -w "\n%{http_code}" \
        -H "Authorization: Bearer $EMP_TOKEN" "$DASH_URL")
    dash_body2=$(echo "$dash_resp2" | head -n -1)
    dash_status2=$(echo "$dash_resp2" | tail -n 1)
    if [ "$dash_status2" -eq 200 ] && echo "$dash_body2" | grep -q '"open":true'; then
        echo "PASS: checkin.open=true after check-in"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected open:true after check-in — $dash_body2"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: check-in failed with HTTP $ci_status — skipping open session test"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Admin calling dashboard for non-existent employee → 404 ───────────
echo "--- Test 8: Caller with no employee profile → 404 ---"
other_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
OTHER_TOKEN=$(echo "$other_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "No employee profile → 404" 404 -s \
    -H "Authorization: Bearer $OTHER_TOKEN" "$DASH_URL"
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
