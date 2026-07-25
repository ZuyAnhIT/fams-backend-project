#!/usr/bin/env bash
# Tests for is_late / late_minutes calculation (Task 81)
# Verifies that the attendance summary correctly flags late check-ins.
# Usage: BASE_URL=http://localhost:8080 bash test_late_detection.sh

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

echo "=== Late Detection Tests (task 81) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup: admin login ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Late Corp ${TS}\",\"slug\":\"late-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HQ","timezone":"UTC","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Shift starts at 00:00 UTC (midnight) so ANY check-in during the day will be "late"
# (since the test runs after midnight). We use start=00:00 end=23:59.
sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Midnight","startTime":"00:00","endTime":"23:59","earlyCheckinMinutes":0,"lateCheckoutMinutes":0,"allowOvertime":false}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift (midnight)"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Second shift: starts at 23:59 (almost end of day) so check-ins are not late
sh2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"LateStart","startTime":"23:59","endTime":"23:59","earlyCheckinMinutes":0,"lateCheckoutMinutes":0,"allowOvertime":false}')
if [ "$(echo "$sh2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift (late-start)"; exit 1; fi
SHIFT2_ID=$(echo "$sh2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

INVITE_EMAIL="late.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Late\",\"lastName\":\"Tester\"}")
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

# Assign employee to "midnight" shift (startTime=00:00 → any check-in after midnight is late)
asgn_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi

# Check-in and check-out to trigger summary
ci_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"dev-late-1\"}")
if [ "$(echo "$ci_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: check-in"; exit 1; fi
CHECKIN_ID=$(echo "$ci_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

co_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN_ID/checkout" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542,"gpsAccuracy":10.0}')
if [ "$(echo "$co_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: check-out"; exit 1; fi

echo "Setup complete. TENANT_ID=$TENANT_ID EMP_ID=$EMP_ID"
echo ""

ATT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/attendance"

# ── Test 1: Summary contains late fields ──────────────────────────────────────
echo "--- Test 1: Summary has late and lateMinutes fields ---"
list_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL?employeeId=$EMP_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_body=$(echo "$list_resp" | head -n -1)
list_status=$(echo "$list_resp" | tail -n 1)
if [ "$list_status" -eq 200 ]; then
    has_late=$(echo "$list_body" | grep -c '"late"' || true)
    has_late_min=$(echo "$list_body" | grep -c '"lateMinutes"' || true)
    if [ "${has_late:-0}" -ge 1 ] && [ "${has_late_min:-0}" -ge 1 ]; then
        echo "PASS: Response contains 'late' and 'lateMinutes' fields"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing late fields — $list_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $list_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: isLate=true when check-in is after shift start (00:00) ────────────
echo "--- Test 2: late=true for shift starting at 00:00 (any check-in after midnight) ---"
if [ "$list_status" -eq 200 ]; then
    is_late_val=$(echo "$list_body" | grep -o '"late":[a-z]*' | head -1 | cut -d: -f2)
    late_min_val=$(echo "$list_body" | grep -o '"lateMinutes":[0-9]*' | head -1 | cut -d: -f2)
    if [ "$is_late_val" = "true" ] && [ "${late_min_val:-0}" -gt 0 ]; then
        echo "PASS: late=true, lateMinutes=$late_min_val (check-in after 00:00 shift start)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected late=true and lateMinutes>0, got late=$is_late_val lateMinutes=$late_min_val"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: list failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: lateMinutes > 0 when late ────────────────────────────────────────
echo "--- Test 3: lateMinutes is a positive integer when late ---"
if [ "$list_status" -eq 200 ]; then
    late_min=$(echo "$list_body" | grep -o '"lateMinutes":[0-9]*' | head -1 | cut -d: -f2)
    if [ "${late_min:-0}" -gt 0 ]; then
        echo "PASS: lateMinutes=$late_min > 0"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected lateMinutes>0, got $late_min"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: list failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Employee /me also includes late fields ────────────────────────────
echo "--- Test 4: Employee /me response includes late fields ---"
me_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL/me" \
    -H "Authorization: Bearer $EMP_TOKEN")
me_body=$(echo "$me_resp" | head -n -1)
me_status=$(echo "$me_resp" | tail -n 1)
if [ "$me_status" -eq 200 ]; then
    has_late=$(echo "$me_body" | grep -c '"late"' || true)
    has_late_min=$(echo "$me_body" | grep -c '"lateMinutes"' || true)
    if [ "${has_late:-0}" -ge 1 ] && [ "${has_late_min:-0}" -ge 1 ]; then
        echo "PASS: /me response contains 'late' and 'lateMinutes'"
        PASS=$((PASS + 1))
    else
        echo "FAIL: /me missing late fields — $me_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $me_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: No shift → late=false, lateMinutes=0 ─────────────────────────────
echo "--- Test 5: No shift linked → late=false, lateMinutes=0 ---"
# Use second employee with no shift assigned
INVITE_EMAIL2="late.emp2.${TS}@example.com"
inv2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL2\",\"firstName\":\"NoShift\",\"lastName\":\"Tester\"}")
if [ "$(echo "$inv2_resp" | tail -n 1)" -ne 201 ]; then
    echo "SKIP: Could not create second employee"
    FAIL=$((FAIL + 1))
else
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

    # Assign WITHOUT a shift
    asgn2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"employeeId\":\"$EMP2_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
    if [ "$(echo "$asgn2_resp" | tail -n 1)" -ne 201 ]; then
        echo "SKIP: Could not create assignment without shift"
        FAIL=$((FAIL + 1))
    else
        ci2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_TOKEN" \
            -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"dev-late-2\"}")
        CHECKIN2_ID=$(echo "$ci2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
        curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN2_ID/checkout" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_TOKEN" \
            -d '{"latitude":21.0285,"longitude":105.8542}'

        no_shift_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL?employeeId=$EMP2_ID" \
            -H "Authorization: Bearer $ADMIN_TOKEN")
        no_shift_body=$(echo "$no_shift_resp" | head -n -1)
        ns_status=$(echo "$no_shift_resp" | tail -n 1)
        if [ "$ns_status" -eq 200 ]; then
            ns_late=$(echo "$no_shift_body" | grep -o '"late":[a-z]*' | head -1 | cut -d: -f2)
            ns_min=$(echo "$no_shift_body" | grep -o '"lateMinutes":[0-9]*' | head -1 | cut -d: -f2)
            if [ "$ns_late" = "false" ] && [ "${ns_min:-1}" -eq 0 ]; then
                echo "PASS: No-shift employee: late=false, lateMinutes=0"
                PASS=$((PASS + 1))
            else
                echo "FAIL: Expected late=false/lateMinutes=0, got late=$ns_late lateMinutes=$ns_min"
                FAIL=$((FAIL + 1))
            fi
        else
            echo "FAIL: no-shift query HTTP $ns_status"
            FAIL=$((FAIL + 1))
        fi
    fi
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
