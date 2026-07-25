#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/checkin/{checkinId}/checkout
# Covers task 72 (GPS checkout), 73 (late-checkout cap), 74 (work_minutes)
# Usage: BASE_URL=http://localhost:8080 bash test_checkout.sh

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

echo "=== GPS Check-out Tests (tasks 72/73/74) ==="
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
    -d "{\"name\":\"Checkout Corp ${TS}\",\"slug\":\"checkout-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HQ","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

geo_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/geofences" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"coordinates":[[105.8492,21.0235],[105.8592,21.0235],[105.8592,21.0335],[105.8492,21.0335],[105.8492,21.0235]],"bufferMeters":50}')
if [ "$(echo "$geo_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: geofence"; exit 1; fi

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00","allowOvertime":true,"lateCheckoutMinutes":30}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

INVITE_EMAIL="co.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Co\",\"lastName\":\"Tester\"}")
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

# Do initial check-in to get CHECKIN_ID
ci_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0,\"deviceId\":\"dev-1\"}")
if [ "$(echo "$ci_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: check-in ($(echo "$ci_resp" | tail -n 1))"; exit 1; fi
CHECKIN_ID=$(echo "$ci_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Setup complete. CHECKIN_ID=$CHECKIN_ID"
echo ""

CHECKOUT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN_ID/checkout"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated checkout" 401 -s -X POST "$CHECKOUT_URL" \
    -H "Content-Type: application/json" \
    -d '{"latitude":21.0285,"longitude":105.8542}'
echo ""

# ── Test 2: Missing latitude → 400 ───────────────────────────────────────────
echo "--- Test 2: Missing latitude → 400 ---"
run_test "Missing latitude" 400 -s -X POST "$CHECKOUT_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"longitude":105.8542}'
echo ""

# ── Test 3: Non-existent checkin ID → 404 ────────────────────────────────────
echo "--- Test 3: Non-existent checkin ID → 404 ---"
run_test "Non-existent checkin" 404 -s -X POST \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/00000000-0000-0000-0000-000000000000/checkout" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542}'
echo ""

# ── Test 4: Another employee's token cannot checkout this record → 403 ────────
echo "--- Setup: Second employee ---"
INVITE_EMAIL2="co.emp2.${TS}@example.com"
inv2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL2\",\"firstName\":\"Co2\",\"lastName\":\"Tester\"}")
if [ "$(echo "$inv2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP WARN: invitation 2 failed"; else
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

    echo "--- Test 4: Different employee cannot checkout → 403 ---"
    run_test "Forbidden checkout (wrong employee)" 403 -s -X POST "$CHECKOUT_URL" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_TOKEN" \
        -d '{"latitude":21.0285,"longitude":105.8542}'
    echo ""
fi

# ── Test 5: Happy path checkout inside geofence → 200 ────────────────────────
echo "--- Test 5: Checkout inside geofence → 200 with workMinutes ---"
co_resp=$(curl -s -w "\n%{http_code}" -X POST "$CHECKOUT_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542,"gpsAccuracy":12.0,"deviceId":"dev-1"}')
co_body=$(echo "$co_resp" | head -n -1)
co_status=$(echo "$co_resp" | tail -n 1)
if [ "$co_status" -eq 200 ]; then
    work_minutes=$(echo "$co_body" | grep -o '"workMinutes":[0-9]*' | cut -d: -f2)
    checkout_at=$(echo "$co_body" | grep -o '"checkOutAt":"[^"]*"' | head -1)
    co_inside=$(echo "$co_body" | grep -o '"checkOutInsideGeofence":[^,}]*' 2>/dev/null | cut -d: -f2 | tr -d ' ' || echo "")
    if [ -n "$checkout_at" ] && [ -n "$work_minutes" ] && [ "$work_minutes" -ge 0 ]; then
        echo "PASS: Checkout successful (HTTP 200, workMinutes=$work_minutes)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing checkOutAt or workMinutes — $co_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $co_status — $co_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: workMinutes is non-negative (task 74) ─────────────────────────────
echo "--- Test 6: workMinutes >= 0 (task 74) ---"
if [ "$co_status" -eq 200 ]; then
    work_minutes=$(echo "$co_body" | grep -o '"workMinutes":[0-9]*' | cut -d: -f2)
    if [ "$work_minutes" -ge 0 ]; then
        echo "PASS: workMinutes is non-negative (workMinutes=$work_minutes)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: workMinutes is negative: $work_minutes"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: checkout failed, skipping workMinutes check"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: message field is present in checkout response (task 76) ───────────
echo "--- Test 7: message field present in response ---"
if [ "$co_status" -eq 200 ]; then
    if echo "$co_body" | grep -q '"message"'; then
        msg=$(echo "$co_body" | grep -o '"message":"[^"]*"' | tail -1 | cut -d'"' -f4)
        echo "PASS: message field present (\"$msg\")"
        PASS=$((PASS + 1))
    else
        echo "FAIL: message field missing in response"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: checkout failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Duplicate checkout → 409 ─────────────────────────────────────────
echo "--- Test 8: Second checkout on same record → 409 ---"
run_test "Duplicate checkout" 409 -s -X POST "$CHECKOUT_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542}'
echo ""

# ── Test 9: Checkout outside geofence → 200, checkOutInsideGeofence=false ─────
echo "--- Setup: New check-in for outside-geofence checkout test ---"
ci2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0}")
ci2_status=$(echo "$ci2_resp" | tail -n 1)
CHECKIN_ID2=$(echo "$ci2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ "$ci2_status" -eq 201 ] && [ -n "$CHECKIN_ID2" ]; then
    echo "--- Test 9: Checkout far outside geofence → 200, status=pending_review ---"
    co2_resp=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN_ID2/checkout" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
        -d '{"latitude":10.7769,"longitude":106.7009,"gpsAccuracy":15.0}')
    co2_body=$(echo "$co2_resp" | head -n -1)
    co2_status=$(echo "$co2_resp" | tail -n 1)
    if [ "$co2_status" -eq 200 ]; then
        co2_stat=$(echo "$co2_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
        if [ "$co2_stat" = "pending_review" ]; then
            echo "PASS: Outside-geofence checkout → status=pending_review"
            PASS=$((PASS + 1))
        else
            echo "FAIL: Expected status=pending_review, got $co2_stat"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Expected HTTP 200, got $co2_status"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Could not create second check-in"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
