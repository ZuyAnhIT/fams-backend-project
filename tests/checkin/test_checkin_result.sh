#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/checkin/{checkinId}
# Covers task 76 (display check-in/out result with message field)
# Usage: BASE_URL=http://localhost:8080 bash test_checkin_result.sh

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

echo "=== Check-in Result Tests (task 76) ==="
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
    -d "{\"name\":\"Result Corp ${TS}\",\"slug\":\"result-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HQ","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

INVITE_EMAIL="res.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Res\",\"lastName\":\"Tester\"}")
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

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"

ci_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542,\"gpsAccuracy\":10.0}")
if [ "$(echo "$ci_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: check-in"; exit 1; fi
CHECKIN_ID=$(echo "$ci_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Setup complete. CHECKIN_ID=$CHECKIN_ID"
echo ""

RESULT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN_ID"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -s "$RESULT_URL"
echo ""

# ── Test 2: Non-existent ID → 404 ────────────────────────────────────────────
echo "--- Test 2: Non-existent checkin ID → 404 ---"
run_test "Non-existent ID" 404 -s \
    -H "Authorization: Bearer $EMP_TOKEN" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/00000000-0000-0000-0000-000000000000"
echo ""

# ── Test 3: Happy path → 200 with all fields ──────────────────────────────────
echo "--- Test 3: Get result → 200 with full record ---"
res_resp=$(curl -s -w "\n%{http_code}" -H "Authorization: Bearer $EMP_TOKEN" "$RESULT_URL")
res_body=$(echo "$res_resp" | head -n -1)
res_status=$(echo "$res_resp" | tail -n 1)
if [ "$res_status" -eq 200 ]; then
    if echo "$res_body" | grep -q '"id"' && echo "$res_body" | grep -q '"status"' && \
       echo "$res_body" | grep -q '"checkInAt"'; then
        echo "PASS: Get result returned full record (HTTP 200)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing fields in result response — $res_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $res_status — $res_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: message field is present and non-empty ────────────────────────────
echo "--- Test 4: message field present and non-empty ---"
if [ "$res_status" -eq 200 ]; then
    if echo "$res_body" | grep -o '"message":"[^"]*"' | tail -1 | grep -q '[^"]'; then
        msg=$(echo "$res_body" | grep -o '"message":"[^"]*"' | tail -1 | cut -d'"' -f4)
        echo "PASS: message field present (\"$msg\")"
        PASS=$((PASS + 1))
    else
        echo "FAIL: message field missing or empty"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: result fetch failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: status=valid for inside-geofence check-in ────────────────────────
echo "--- Test 5: status=valid (no geofence configured on this site) ---"
if [ "$res_status" -eq 200 ]; then
    status_val=$(echo "$res_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ "$status_val" = "valid" ]; then
        echo "PASS: status=valid"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected status=valid, got $status_val"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: checkOutAt is null before checkout ────────────────────────────────
echo "--- Test 6: checkOutAt is null before checkout ---"
if [ "$res_status" -eq 200 ]; then
    if echo "$res_body" | grep -q '"checkOutAt":null'; then
        echo "PASS: checkOutAt is null before checkout"
        PASS=$((PASS + 1))
    else
        echo "FAIL: checkOutAt should be null before checkout — $res_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: message changes after checkout ────────────────────────────────────
echo "--- Test 7: message changes after checkout ---"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN_ID/checkout" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"latitude":21.0285,"longitude":105.8542}'
after_resp=$(curl -s -w "\n%{http_code}" -H "Authorization: Bearer $EMP_TOKEN" "$RESULT_URL")
after_body=$(echo "$after_resp" | head -n -1)
after_status=$(echo "$after_resp" | tail -n 1)
if [ "$after_status" -eq 200 ]; then
    # Use tail -1 to get data.message (the outer ApiResponse.message="Success" is first)
    msg_after=$(echo "$after_body" | grep -o '"message":"[^"]*"' | tail -1 | cut -d'"' -f4)
    # After checkout the message should mention work minutes
    if echo "$msg_after" | grep -qi "check-out\|worked\|minutes"; then
        echo "PASS: message updated after checkout (\"$msg_after\")"
        PASS=$((PASS + 1))
    else
        echo "FAIL: message after checkout does not mention checkout/minutes: \"$msg_after\""
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Could not fetch result after checkout (HTTP $after_status)"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Different employee cannot view record → 403 ──────────────────────
echo "--- Test 8: Different employee cannot view record → 403 ---"
INVITE_EMAIL2="res.emp2.${TS}@example.com"
inv2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL2\",\"firstName\":\"Res2\",\"lastName\":\"Tester\"}")
if [ "$(echo "$inv2_resp" | tail -n 1)" -eq 201 ]; then
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
    run_test "Other employee forbidden" 403 -s \
        -H "Authorization: Bearer $EMP2_TOKEN" "$RESULT_URL"
else
    echo "SKIP: Could not create second employee"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
