#!/usr/bin/env bash
# Tests for Task 71 — Early check-in validation
# POST /api/v1/tenants/{tenantId}/checkin must reject check-ins before
# the allowed early window (shiftStart - earlyCheckinMinutes).
# Usage: BASE_URL=http://localhost:8080 bash test_early_checkin.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Early Check-in Validation Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: admin login ────────────────────────────────────────────────────────
echo "--- Setup: Login ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Early Corp ${TS}\",\"slug\":\"early-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HQ","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "tenant=$TENANT_ID  site=$SITE_ID"
echo ""

# ── Helper: invite + accept + login → returns token ──────────────────────────
invite_and_login() {
    local email="$1"
    local inv_r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d "{\"email\":\"$email\",\"firstName\":\"Test\",\"lastName\":\"User\"}")
    if [ "$(echo "$inv_r" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invite $email"; exit 1; fi

    local tok=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT token FROM employee_invitations WHERE email='$email' AND status='pending' LIMIT 1;" \
        | tr -d ' \n')
    curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
        -H "Content-Type: application/json" \
        -d "{\"token\":\"$tok\",\"password\":\"Employee@1234\"}"

    local login_r=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$email\",\"password\":\"Employee@1234\"}")
    echo "$(echo "$login_r" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)"
}

get_employee_id() {
    local email="$1"
    docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$email' AND e.deleted_at IS NULL LIMIT 1;" \
        | tr -d ' \n'
}

CHECKIN_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin"

# ── Test 1: Assignment without shift — no early check-in rule → 201 ───────────
echo "--- Test 1: No shift on assignment — early check-in check skipped → 201 ---"
T1_EMAIL="t1.early.${TS}@example.com"
T1_TOKEN=$(invite_and_login "$T1_EMAIL")
T1_EMP=$(get_employee_id "$T1_EMAIL")

# Create assignment WITHOUT a shift
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$T1_EMP\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"

run_test "No shift — no early check-in block" 201 \
    -s -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $T1_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}"
echo ""

# ── Test 2: Shift start at 00:01, earlyCheckinMinutes=0 — shift already started → 201 ─
echo "--- Test 2: Shift started at 00:01, earlyCheckinMinutes=0 — already started → 201 ---"
# Shift that starts at 00:01 is "in the past" for any time during the workday
sh2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Night Owl","startTime":"00:01","endTime":"23:59"}')
if [ "$(echo "$sh2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift 2"; exit 1; fi
SHIFT2_ID=$(echo "$sh2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

T2_EMAIL="t2.early.${TS}@example.com"
T2_TOKEN=$(invite_and_login "$T2_EMAIL")
T2_EMP=$(get_employee_id "$T2_EMAIL")

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$T2_EMP\",\"shiftId\":\"$SHIFT2_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"

run_test "Shift 00:01 already started" 201 \
    -s -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $T2_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}"
echo ""

# ── Test 3: Shift starts at 23:59, earlyCheckinMinutes=0 — too early → 422 ───
echo "--- Test 3: Shift at 23:59, earlyCheckinMinutes=0 — too early → 422 ---"
sh3_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Late Shift","startTime":"23:59","endTime":"23:59"}')
if [ "$(echo "$sh3_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift 3"; exit 1; fi
SHIFT3_ID=$(echo "$sh3_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

T3_EMAIL="t3.early.${TS}@example.com"
T3_TOKEN=$(invite_and_login "$T3_EMAIL")
T3_EMP=$(get_employee_id "$T3_EMAIL")

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$T3_EMP\",\"shiftId\":\"$SHIFT3_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"

run_test "Shift 23:59, earlyCheckinMinutes=0 — too early" 422 \
    -s -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $T3_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}"
echo ""

# ── Test 4: Error message is informative ──────────────────────────────────────
echo "--- Test 4: 422 response body contains shift start time ---"
err_resp=$(curl -s -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $T3_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}")
if echo "$err_resp" | grep -q "23:59"; then
    echo "PASS: Error message contains shift start time"
    PASS=$((PASS + 1))
else
    echo "FAIL: Error message missing shift time: $err_resp"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Shift at 23:59, earlyCheckinMinutes=1439 — large window → 201 ────
# 1439 min = 23h59m → allowedFrom = 23:59 - 23h59m = 00:00, so always in window.
# NOTE: 1440 (24h) wraps LocalTime back to 23:59 (no change), so use 1439.
echo "--- Test 5: Shift 23:59, earlyCheckinMinutes=1439 (allowedFrom=00:00) → 201 ---"
curl -s -o /dev/null -X PUT \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts/$SHIFT3_ID/ot-config" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"earlyCheckinMinutes":1439}'

# Need a fresh employee (T3 is blocked by "too early" and has no open session anyway)
T5_EMAIL="t5.early.${TS}@example.com"
T5_TOKEN=$(invite_and_login "$T5_EMAIL")
T5_EMP=$(get_employee_id "$T5_EMAIL")

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$T5_EMP\",\"shiftId\":\"$SHIFT3_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"

run_test "earlyCheckinMinutes=1439 allows check-in any time" 201 \
    -s -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $T5_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}"
echo ""

# ── Test 6: Shift at 23:59, earlyCheckinMinutes=30 — still too early → 422 ───
echo "--- Test 6: Shift 23:59, earlyCheckinMinutes=30 (allowedFrom=23:29) → 422 ---"
sh6_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Evening Shift","startTime":"23:59","endTime":"23:59"}')
if [ "$(echo "$sh6_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift 6"; exit 1; fi
SHIFT6_ID=$(echo "$sh6_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

curl -s -o /dev/null -X PUT \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts/$SHIFT6_ID/ot-config" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"earlyCheckinMinutes":30}'

T6_EMAIL="t6.early.${TS}@example.com"
T6_TOKEN=$(invite_and_login "$T6_EMAIL")
T6_EMP=$(get_employee_id "$T6_EMAIL")

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$T6_EMP\",\"shiftId\":\"$SHIFT6_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"

run_test "Shift 23:59, earlyCheckinMinutes=30, allowedFrom=23:29 — still too early" 422 \
    -s -X POST "$CHECKIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $T6_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}"
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
