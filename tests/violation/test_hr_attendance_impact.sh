#!/usr/bin/env bash
# Tests for PATCH /api/v1/tenants/{tenantId}/violations/{violationId}/attendance-impact
# Covers task 118 (HR updates whether a violation affects attendance)
# Usage: BASE_URL=http://localhost:8080 bash test_hr_attendance_impact.sh

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

echo "=== HR Attendance Impact Tests (task 118) ==="
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
    -d "{\"ownerEmail\":\"admin@fams.com\",\"name\":\"Impact Corp ${TS}\",\"slug\":\"impact-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

INVITE_EMAIL="impact.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Impact\",\"lastName\":\"Emp\"}")
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

SITE_ID=$(cat /proc/sys/kernel/random/uuid)

seed_violation() {
    docker exec fams-postgres psql -U fams_user -d fams_db -c \
        "INSERT INTO violations (id, tenant_id, employee_id, site_id, violation_type, check_date, resolved, affects_attendance, created_at, updated_at)
         VALUES (uuid_generate_v4(), '$TENANT_ID', '$EMP_ID', '$SITE_ID', 'no_response', '2026-07-04', false, false, now(), now());" \
        > /dev/null
    docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
        "SELECT id FROM violations WHERE tenant_id='$TENANT_ID' AND employee_id='$EMP_ID' ORDER BY created_at DESC LIMIT 1;" \
        | tr -d ' \n'
}

VIOLATION_ID=$(seed_violation)
echo "Setup complete. TENANT_ID=$TENANT_ID  VIOLATION_ID=$VIOLATION_ID"
echo ""

IMPACT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/violations/$VIOLATION_ID/attendance-impact"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -s -X PATCH "$IMPACT_URL" \
    -H "Content-Type: application/json" \
    -d '{"affectsAttendance":true}'
echo ""

# ── Test 2: Employee token (no violations:update perm) → 403 ─────────────────
echo "--- Test 2: Employee without violations:update → 403 ---"
run_test "Employee forbidden" 403 -s -X PATCH "$IMPACT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"affectsAttendance":true}'
echo ""

# ── Test 3: Missing body → 400 ────────────────────────────────────────────────
echo "--- Test 3: No body → 400 ---"
run_test "No body rejected" 400 -s -X PATCH "$IMPACT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 4: null affectsAttendance → 400 ──────────────────────────────────────
echo "--- Test 4: null affectsAttendance → 400 ---"
run_test "Null value rejected" 400 -s -X PATCH "$IMPACT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"affectsAttendance":null}'
echo ""

# ── Test 5: Set affectsAttendance=true → 200 ─────────────────────────────────
echo "--- Test 5: Set affectsAttendance=true → 200 ---"
set_resp=$(curl -s -w "\n%{http_code}" -X PATCH "$IMPACT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"affectsAttendance":true}')
set_body=$(echo "$set_resp" | head -n -1)
set_status=$(echo "$set_resp" | tail -n 1)
if [ "$set_status" -eq 200 ]; then
    echo "PASS: Set returned HTTP 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $set_status — $set_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Response has affectsAttendance=true ───────────────────────────────
echo "--- Test 6: Response reflects affectsAttendance=true ---"
if [ "$set_status" -eq 200 ]; then
    if echo "$set_body" | grep -q '"affectsAttendance":true'; then
        echo "PASS: affectsAttendance=true in response"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected affectsAttendance:true — $set_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: DB state confirms affectsAttendance=true ─────────────────────────
echo "--- Test 7: DB state shows affects_attendance=true ---"
db_val=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT affects_attendance FROM violations WHERE id='$VIOLATION_ID';" | tr -d ' \n')
if [ "$db_val" = "t" ]; then
    echo "PASS: DB shows affects_attendance=t"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected t, got '$db_val'"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Toggle back to false → 200 ───────────────────────────────────────
echo "--- Test 8: Set affectsAttendance=false (toggle off) → 200 ---"
clear_resp=$(curl -s -w "\n%{http_code}" -X PATCH "$IMPACT_URL" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"affectsAttendance":false}')
clear_body=$(echo "$clear_resp" | head -n -1)
clear_status=$(echo "$clear_resp" | tail -n 1)
if [ "$clear_status" -eq 200 ]; then
    if echo "$clear_body" | grep -q '"affectsAttendance":false'; then
        echo "PASS: Toggle off returned HTTP 200 with affectsAttendance=false"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected affectsAttendance:false — $clear_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $clear_status — $clear_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: Works on a resolved (confirmed) violation too ─────────────────────
echo "--- Test 9: Update impact on a confirmed violation → 200 ---"
VIOLATION_ID2=$(seed_violation)
docker exec fams-postgres psql -U fams_user -d fams_db -c \
    "UPDATE violations SET resolved=true, resolution='confirmed', resolved_at=now(), resolved_by='$EMP_ID' WHERE id='$VIOLATION_ID2';" \
    > /dev/null
conf_resp=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/violations/$VIOLATION_ID2/attendance-impact" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"affectsAttendance":true}')
if [ "$conf_resp" -eq 200 ]; then
    echo "PASS: Impact update allowed on resolved violation (HTTP 200)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 200 on resolved violation, got $conf_resp"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 10: Non-existent violation → 404 ────────────────────────────────────
echo "--- Test 10: Non-existent violationId → 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000099"
run_test "Violation not found" 404 -s -X PATCH \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/violations/$FAKE_ID/attendance-impact" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"affectsAttendance":true}'
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
