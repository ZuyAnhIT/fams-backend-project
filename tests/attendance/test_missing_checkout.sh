#!/usr/bin/env bash
# Tests for missing_checkout detection (Task 84)
# Verifies the field is present and correctly set for same-day sessions.
# The missing_checkout=true case requires a past-day open session, which the
# nightly job sets. Here we verify: field exists, false for today's sessions,
# and the /recompute endpoint works.
# Usage: BASE_URL=http://localhost:8080 bash test_missing_checkout.sh

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

echo "=== Missing Checkout Tests (task 84) ==="
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

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"MC Corp ${TS}\",\"slug\":\"mc-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HQ","timezone":"UTC","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"00:00","endTime":"23:59"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

INVITE_EMAIL="mc.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"MC\",\"lastName\":\"Tester\"}")
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

TODAY=$(date -u +%Y-%m-%d)
YESTERDAY=$(date -u -d "yesterday" +%Y-%m-%d 2>/dev/null || date -u -v-1d +%Y-%m-%d)

echo "Setup complete. TENANT_ID=$TENANT_ID EMP_ID=$EMP_ID TODAY=$TODAY"
echo ""

ATT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/attendance"

# ── Test 1: Completed session → missingCheckout=false ─────────────────────────
echo "--- Test 1: Completed session → missingCheckout=false ---"
ci1_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"dev-mc-1\"}")
if [ "$(echo "$ci1_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: check-in 1"; exit 1; fi
CI1_ID=$(echo "$ci1_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CI1_ID/checkout" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542}'

done_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL?employeeId=$EMP_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
done_body=$(echo "$done_resp" | head -n -1)
done_status=$(echo "$done_resp" | tail -n 1)
if [ "$done_status" -eq 200 ]; then
    has_mc=$(echo "$done_body" | grep -c '"missingCheckout"' || true)
    mc_val=$(echo "$done_body" | grep -o '"missingCheckout":[a-z]*' | head -1 | cut -d: -f2)
    if [ "${has_mc:-0}" -ge 1 ] && [ "$mc_val" = "false" ]; then
        echo "PASS: Completed session: missingCheckout=false"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected missingCheckout=false, got '$mc_val' (has_mc=${has_mc:-0})"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $done_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Response contains missingCheckout field ───────────────────────────
echo "--- Test 2: Response has missingCheckout field ---"
if [ "$done_status" -eq 200 ]; then
    has_mc=$(echo "$done_body" | grep -c '"missingCheckout"' || true)
    if [ "${has_mc:-0}" -ge 1 ]; then
        echo "PASS: Response contains 'missingCheckout' field"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing 'missingCheckout' field — $done_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: list failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Today's open session → missingCheckout=false (still working) ─────
echo "--- Test 3: Today's open session → missingCheckout=false (in progress) ---"
ci2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"dev-mc-2\"}")
if [ "$(echo "$ci2_resp" | tail -n 1)" -ne 201 ]; then
    echo "SKIP: Could not open a second check-in session"
    FAIL=$((FAIL + 1))
else
    open_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL?employeeId=$EMP_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN")
    open_body=$(echo "$open_resp" | head -n -1)
    open_status=$(echo "$open_resp" | tail -n 1)
    if [ "$open_status" -eq 200 ]; then
        mc_open=$(echo "$open_body" | grep -o '"missingCheckout":[a-z]*' | head -1 | cut -d: -f2)
        summ_status=$(echo "$open_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
        if [ "$mc_open" = "false" ] && [ "$summ_status" = "incomplete" ]; then
            echo "PASS: Open same-day session: missingCheckout=false, status=incomplete"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Expected missingCheckout=false/status=incomplete, got missingCheckout=$mc_open status=$summ_status"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Expected HTTP 200, got $open_status"
        FAIL=$((FAIL + 1))
    fi
fi
echo ""

# ── Test 4: /recompute endpoint → 200 for admin ───────────────────────────────
echo "--- Test 4: POST /recompute with yesterday's date → 200 (admin) ---"
run_test "Admin can trigger recompute" 200 -s -X POST \
    "$ATT_URL/recompute?date=$YESTERDAY" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 5: /recompute → 401 without token ────────────────────────────────────
echo "--- Test 5: /recompute without token → 401 ---"
run_test "Unauthenticated recompute" 401 -s -X POST \
    "$ATT_URL/recompute?date=$YESTERDAY"
echo ""

# ── Test 6: /recompute → 403 for employee ─────────────────────────────────────
echo "--- Test 6: /recompute with employee token → 403 ---"
run_test "Employee cannot trigger recompute" 403 -s -X POST \
    "$ATT_URL/recompute?date=$YESTERDAY" \
    -H "Authorization: Bearer $EMP_TOKEN"
echo ""

# ── Test 7: GET /{summaryId} returns correct missingCheckout field ────────────
echo "--- Test 7: GET /{summaryId} returns missingCheckout=false for completed session ---"
# Use the summary from Test 1 (completed session)
SUMMARY_ID=$(echo "$done_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$SUMMARY_ID" ]; then
    echo "FAIL: Could not extract summaryId from list response"
    FAIL=$((FAIL + 1))
else
    single_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL/$SUMMARY_ID" \
        -H "Authorization: Bearer $ADMIN_TOKEN")
    single_body=$(echo "$single_resp" | head -n -1)
    single_status=$(echo "$single_resp" | tail -n 1)
    if [ "$single_status" -eq 200 ]; then
        mc_val=$(echo "$single_body" | grep -o '"missingCheckout":[a-z]*' | head -1 | cut -d: -f2)
        if [ "$mc_val" = "false" ]; then
            echo "PASS: GET /{summaryId}: missingCheckout=false for completed session"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Expected missingCheckout=false, got '$mc_val' — $single_body"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Expected HTTP 200, got $single_status — $single_body"
        FAIL=$((FAIL + 1))
    fi
fi
echo ""

# ── Test 8: Employee /me includes missingCheckout field ───────────────────────
echo "--- Test 8: Employee /me includes missingCheckout field ---"
me_resp=$(curl -s -w "\n%{http_code}" "$ATT_URL/me" \
    -H "Authorization: Bearer $EMP_TOKEN")
me_body=$(echo "$me_resp" | head -n -1)
me_status=$(echo "$me_resp" | tail -n 1)
if [ "$me_status" -eq 200 ]; then
    has_mc=$(echo "$me_body" | grep -c '"missingCheckout"' || true)
    if [ "${has_mc:-0}" -ge 1 ]; then
        echo "PASS: /me response contains 'missingCheckout'"
        PASS=$((PASS + 1))
    else
        echo "FAIL: /me missing 'missingCheckout' — $me_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $me_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
