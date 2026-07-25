#!/usr/bin/env bash
# Tests for is_early_leave / early_leave_minutes calculation (Task 82)
# Verifies that the attendance summary correctly flags early departures.
# Usage: BASE_URL=http://localhost:8080 bash test_early_leave.sh

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

echo "=== Early Leave Tests (task 82) ==="
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
    -d "{\"name\":\"EL Corp ${TS}\",\"slug\":\"el-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HQ","timezone":"UTC","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Shift ends at 23:59 — any checkout before that counts as early leave
sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"LongDay","startTime":"00:00","endTime":"23:59","earlyCheckinMinutes":0,"lateCheckoutMinutes":0,"allowOvertime":false}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

INVITE_EMAIL="el.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"EL\",\"lastName\":\"Tester\"}")
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

asgn_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi

# Check-in and check-out (checkout now, which is before 23:59 → early leave)
ci_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"dev-el-1\"}")
if [ "$(echo "$ci_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: check-in"; exit 1; fi
CHECKIN_ID=$(echo "$ci_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

co_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN_ID/checkout" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542,"gpsAccuracy":10.0}')
if [ "$(echo "$co_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: check-out"; exit 1; fi

echo "Setup complete. TENANT_ID=$TENANT_ID EMP_ID=$EMP_ID"
echo ""

ATT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/attendance"

# ── Test 1: Summary contains earlyLeave and earlyLeaveMinutes fields ──────────
echo "--- Test 1: Summary has earlyLeave and earlyLeaveMinutes fields ---"
list_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL?employeeId=$EMP_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_body=$(echo "$list_resp" | head -n -1)
list_status=$(echo "$list_resp" | tail -n 1)
if [ "$list_status" -eq 200 ]; then
    has_el=$(echo "$list_body" | grep -c '"earlyLeave"' || true)
    has_el_min=$(echo "$list_body" | grep -c '"earlyLeaveMinutes"' || true)
    if [ "${has_el:-0}" -ge 1 ] && [ "${has_el_min:-0}" -ge 1 ]; then
        echo "PASS: Response contains 'earlyLeave' and 'earlyLeaveMinutes' fields"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing earlyLeave fields — $list_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $list_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: earlyLeave=true when checkout before 23:59 ────────────────────────
echo "--- Test 2: earlyLeave=true for shift ending at 23:59 (checkout is now) ---"
if [ "$list_status" -eq 200 ]; then
    el_val=$(echo "$list_body" | grep -o '"earlyLeave":[a-z]*' | head -1 | cut -d: -f2)
    el_min=$(echo "$list_body" | grep -o '"earlyLeaveMinutes":[0-9]*' | head -1 | cut -d: -f2)
    if [ "$el_val" = "true" ] && [ "${el_min:-0}" -gt 0 ]; then
        echo "PASS: earlyLeave=true, earlyLeaveMinutes=$el_min (checkout before 23:59)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected earlyLeave=true and earlyLeaveMinutes>0, got earlyLeave=$el_val earlyLeaveMinutes=$el_min"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: list failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: earlyLeaveMinutes is positive ─────────────────────────────────────
echo "--- Test 3: earlyLeaveMinutes > 0 ---"
if [ "$list_status" -eq 200 ]; then
    el_min=$(echo "$list_body" | grep -o '"earlyLeaveMinutes":[0-9]*' | head -1 | cut -d: -f2)
    if [ "${el_min:-0}" -gt 0 ]; then
        echo "PASS: earlyLeaveMinutes=$el_min > 0"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected earlyLeaveMinutes>0, got $el_min"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: list failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Employee /me also includes early leave fields ─────────────────────
echo "--- Test 4: Employee /me response includes earlyLeave fields ---"
me_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL/me" \
    -H "Authorization: Bearer $EMP_TOKEN")
me_body=$(echo "$me_resp" | head -n -1)
me_status=$(echo "$me_resp" | tail -n 1)
if [ "$me_status" -eq 200 ]; then
    has_el=$(echo "$me_body" | grep -c '"earlyLeave"' || true)
    has_el_min=$(echo "$me_body" | grep -c '"earlyLeaveMinutes"' || true)
    if [ "${has_el:-0}" -ge 1 ] && [ "${has_el_min:-0}" -ge 1 ]; then
        echo "PASS: /me response contains 'earlyLeave' and 'earlyLeaveMinutes'"
        PASS=$((PASS + 1))
    else
        echo "FAIL: /me missing earlyLeave fields — $me_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $me_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: No shift → earlyLeave=false, earlyLeaveMinutes=0 ──────────────────
echo "--- Test 5: No shift linked → earlyLeave=false, earlyLeaveMinutes=0 ---"
INVITE_EMAIL2="el.emp2.${TS}@example.com"
inv2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL2\",\"firstName\":\"NoShift\",\"lastName\":\"EL\"}")
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

    asgn2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"employeeId\":\"$EMP2_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
    if [ "$(echo "$asgn2_resp" | tail -n 1)" -ne 201 ]; then
        echo "SKIP: Could not create no-shift assignment"
        FAIL=$((FAIL + 1))
    else
        ci2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_TOKEN" \
            -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"dev-el-2\"}")
        CHECKIN2_ID=$(echo "$ci2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
        curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN2_ID/checkout" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_TOKEN" \
            -d '{"latitude":21.0285,"longitude":105.8542}'

        ns_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL?employeeId=$EMP2_ID" \
            -H "Authorization: Bearer $ADMIN_TOKEN")
        ns_body=$(echo "$ns_resp" | head -n -1)
        ns_status=$(echo "$ns_resp" | tail -n 1)
        if [ "$ns_status" -eq 200 ]; then
            ns_el=$(echo "$ns_body" | grep -o '"earlyLeave":[a-z]*' | head -1 | cut -d: -f2)
            ns_el_min=$(echo "$ns_body" | grep -o '"earlyLeaveMinutes":[0-9]*' | head -1 | cut -d: -f2)
            if [ "$ns_el" = "false" ] && [ "${ns_el_min:-1}" -eq 0 ]; then
                echo "PASS: No-shift employee: earlyLeave=false, earlyLeaveMinutes=0"
                PASS=$((PASS + 1))
            else
                echo "FAIL: Expected earlyLeave=false/earlyLeaveMinutes=0, got earlyLeave=$ns_el earlyLeaveMinutes=$ns_el_min"
                FAIL=$((FAIL + 1))
            fi
        else
            echo "FAIL: no-shift query HTTP $ns_status"
            FAIL=$((FAIL + 1))
        fi
    fi
fi
echo ""

# ── Test 6: Open session (no checkout) → earlyLeave=false ────────────────────
echo "--- Test 6: Open session (incomplete) → earlyLeave=false ---"
INVITE_EMAIL3="el.emp3.${TS}@example.com"
inv3_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL3\",\"firstName\":\"Open\",\"lastName\":\"Session\"}")
if [ "$(echo "$inv3_resp" | tail -n 1)" -ne 201 ]; then
    echo "SKIP: Could not create third employee"
    FAIL=$((FAIL + 1))
else
    INV_TOKEN3=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL3' AND status='pending' LIMIT 1;" \
        | tr -d ' \n')
    curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
        -H "Content-Type: application/json" \
        -d "{\"token\":\"$INV_TOKEN3\",\"password\":\"Employee@1234\"}"

    emp3_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$INVITE_EMAIL3\",\"password\":\"Employee@1234\"}")
    EMP3_TOKEN=$(echo "$emp3_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

    EMP3_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$INVITE_EMAIL3' AND e.deleted_at IS NULL LIMIT 1;" \
        | tr -d ' \n')

    asgn3_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"employeeId\":\"$EMP3_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
    if [ "$(echo "$asgn3_resp" | tail -n 1)" -ne 201 ]; then
        echo "SKIP: Could not create third assignment"
        FAIL=$((FAIL + 1))
    else
        # Check-in only (no checkout) — triggers summary on checkin? Actually summary is only created on checkout.
        # Instead, we manually trigger by check-in. But the summary is created on checkout.
        # So we need to trigger it via checkout then open a new session.
        # Simpler: just check-in (no checkout) — the nightly job would create it.
        # For testing, do checkin, then force-compute via a checkout of a second session after reopening.
        # Actually the simplest test: check-in (incomplete) → summary won't be created yet.
        # Let's check-in then check-out, then check-in again (open session).
        ci3a_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $EMP3_TOKEN" \
            -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"dev-el-3\"}")
        CI3A_ID=$(echo "$ci3a_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
        # Checkout first session to create summary
        curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CI3A_ID/checkout" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $EMP3_TOKEN" \
            -d '{"latitude":21.0285,"longitude":105.8542}'
        # Now check-in a second time without checkout → summary becomes incomplete
        curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $EMP3_TOKEN" \
            -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"dev-el-3b\"}"

        open_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL?employeeId=$EMP3_ID" \
            -H "Authorization: Bearer $ADMIN_TOKEN")
        open_body=$(echo "$open_resp" | head -n -1)
        open_status=$(echo "$open_resp" | tail -n 1)
        if [ "$open_status" -eq 200 ]; then
            open_el=$(echo "$open_body" | grep -o '"earlyLeave":[a-z]*' | head -1 | cut -d: -f2)
            open_summary_status=$(echo "$open_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
            if [ "$open_el" = "false" ] && [ "$open_summary_status" = "incomplete" ]; then
                echo "PASS: Open session: earlyLeave=false, status=incomplete"
                PASS=$((PASS + 1))
            else
                echo "FAIL: Expected earlyLeave=false/status=incomplete, got earlyLeave=$open_el status=$open_summary_status"
                FAIL=$((FAIL + 1))
            fi
        else
            echo "FAIL: open-session query HTTP $open_status"
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
