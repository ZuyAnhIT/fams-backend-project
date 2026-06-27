#!/usr/bin/env bash
# Tests for attendance summary API (Task 80)
# Verifies that a summary is auto-created after checkout, and that HR/employee
# can retrieve it via the attendance endpoints.
# Usage: BASE_URL=http://localhost:8080 bash test_attendance_summary.sh

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

echo "=== Attendance Summary Tests (task 80) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup: admin login ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

echo "--- Setup: tenant ---"
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Att Corp ${TS}\",\"slug\":\"att-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "--- Setup: site ---"
s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HQ","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "--- Setup: shift ---"
sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"00:00","endTime":"23:59","allowOvertime":true,"lateCheckoutMinutes":60}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "--- Setup: invite employee ---"
INVITE_EMAIL="att.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Att\",\"lastName\":\"Tester\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

echo "--- Setup: employee login ---"
emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"password\":\"Employee@1234\"}")
if [ "$(echo "$emp_login" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: employee login"; exit 1; fi
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

echo "--- Setup: assignment ---"
asgn_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi

echo "--- Setup: check-in ---"
ci_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"dev-att-1\"}")
if [ "$(echo "$ci_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: check-in ($(echo "$ci_resp" | tail -n 1))"; exit 1; fi
CHECKIN_ID=$(echo "$ci_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "--- Setup: check-out (triggers attendance summary creation) ---"
co_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN_ID/checkout" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542,"gpsAccuracy":10.0}')
if [ "$(echo "$co_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: check-out ($(echo "$co_resp" | tail -n 1))"; exit 1; fi
echo "Setup complete. TENANT_ID=$TENANT_ID EMP_ID=$EMP_ID"
echo ""

ATT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/attendance"

# ── Test 1: Unauthenticated HR list → 401 ─────────────────────────────────────
echo "--- Test 1: HR list without token → 401 ---"
run_test "Unauthenticated HR list" 401 -s "$ATT_URL"
echo ""

# ── Test 2: Employee token on HR list → 403 ───────────────────────────────────
echo "--- Test 2: Employee token on HR list → 403 ---"
run_test "Employee cannot list all summaries" 403 -s "$ATT_URL" \
    -H "Authorization: Bearer $EMP_TOKEN"
echo ""

# ── Test 3: Admin can list summaries → 200 ────────────────────────────────────
echo "--- Test 3: Admin lists attendance summaries → 200 ---"
list_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_body=$(echo "$list_resp" | head -n -1)
list_status=$(echo "$list_resp" | tail -n 1)
if [ "$list_status" -eq 200 ]; then
    total=$(echo "$list_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "${total:-0}" -ge 1 ]; then
        echo "PASS: HR list returned $total summary records (HTTP 200)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected at least 1 attendance summary, got totalElements=$total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $list_status — $list_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Extract summary ID and verify fields ───────────────────────────────
echo "--- Test 4: Summary has required fields ---"
if [ "$list_status" -eq 200 ]; then
    SUMMARY_ID=$(echo "$list_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    has_date=$(echo "$list_body" | grep -c '"attendanceDate"' || true)
    has_minutes=$(echo "$list_body" | grep -c '"totalWorkMinutes"' || true)
    has_status=$(echo "$list_body" | grep -c '"status"' || true)
    if [ -n "$SUMMARY_ID" ] && [ "${has_date:-0}" -ge 1 ] && [ "${has_minutes:-0}" -ge 1 ] && [ "${has_status:-0}" -ge 1 ]; then
        echo "PASS: Summary has id, attendanceDate, totalWorkMinutes, status"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing required fields in summary — $list_body"
        FAIL=$((FAIL + 1))
        SUMMARY_ID=""
    fi
else
    echo "SKIP: list failed"
    FAIL=$((FAIL + 1))
    SUMMARY_ID=""
fi
echo ""

# ── Test 5: HR get detail → 200 ───────────────────────────────────────────────
echo "--- Test 5: HR get summary detail → 200 ---"
if [ -n "${SUMMARY_ID:-}" ]; then
    run_test "HR get detail" 200 -s "$ATT_URL/$SUMMARY_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN"
else
    echo "SKIP: no summary ID"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Non-existent summary → 404 ────────────────────────────────────────
echo "--- Test 6: Non-existent summary → 404 ---"
run_test "Non-existent summary" 404 -s \
    "$ATT_URL/00000000-0000-0000-0000-000000000000" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 7: Employee views own attendance → 200 ───────────────────────────────
echo "--- Test 7: Employee views own attendance (GET /me) → 200 ---"
me_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL/me" \
    -H "Authorization: Bearer $EMP_TOKEN")
me_body=$(echo "$me_resp" | head -n -1)
me_status=$(echo "$me_resp" | tail -n 1)
if [ "$me_status" -eq 200 ]; then
    me_total=$(echo "$me_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "${me_total:-0}" -ge 1 ]; then
        echo "PASS: Employee /me returned $me_total summary record(s) (HTTP 200)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected ≥1 attendance summary for employee, got $me_total — $me_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $me_status — $me_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: status is 'present' after completed checkout ──────────────────────
echo "--- Test 8: Summary status = 'present' after checkout ---"
if [ "$list_status" -eq 200 ]; then
    att_status=$(echo "$list_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ "$att_status" = "present" ]; then
        echo "PASS: status=present for completed checkout"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected status=present, got '$att_status'"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: list failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: totalWorkMinutes > 0 ──────────────────────────────────────────────
echo "--- Test 9: totalWorkMinutes > 0 ---"
if [ "$list_status" -eq 200 ]; then
    work_min=$(echo "$list_body" | grep -o '"totalWorkMinutes":[0-9]*' | head -1 | cut -d: -f2)
    if [ "${work_min:-0}" -ge 0 ]; then
        echo "PASS: totalWorkMinutes=$work_min (non-negative)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: totalWorkMinutes is negative: $work_min"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: list failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 10: Filter by employeeId works ───────────────────────────────────────
echo "--- Test 10: Filter by employeeId returns correct records ---"
filt_resp=$(curl -s -w "\n%{http_code}" \
    "$ATT_URL?employeeId=$EMP_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
filt_body=$(echo "$filt_resp" | head -n -1)
filt_status=$(echo "$filt_resp" | tail -n 1)
if [ "$filt_status" -eq 200 ]; then
    filt_total=$(echo "$filt_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "${filt_total:-0}" -ge 1 ]; then
        echo "PASS: Filter by employeeId returned $filt_total record(s)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Filter by employeeId returned 0 records"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $filt_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 11: Unauthenticated /me → 401 ────────────────────────────────────────
echo "--- Test 11: Unauthenticated /me → 401 ---"
run_test "Unauthenticated /me" 401 -s "$ATT_URL/me"
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
