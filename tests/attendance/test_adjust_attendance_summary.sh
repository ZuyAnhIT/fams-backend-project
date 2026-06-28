#!/usr/bin/env bash
# Tests for HR adjust attendance summary (task 112)
# PATCH /api/v1/tenants/{tenantId}/attendance/{summaryId}/adjust
# Usage: BASE_URL=http://localhost:8080 bash test_adjust_attendance_summary.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1" expected_status="$2"
    local actual_status
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${@:3}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

check_val() {
    local name="$1" actual="$2" expected="$3"
    if [ "$actual" = "$expected" ]; then
        echo "PASS: $name (=$expected)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected '$expected', got '$actual'"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== HR Adjust Attendance Summary Tests (task 112) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Adjust Corp ${TS}\",\"slug\":\"adjust-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Adjust Site\",\"code\":\"AS-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_EMAIL="adjust.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"Adjust\",\"lastName\":\"Emp\"}"
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"
EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$EMP_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

# Seed an attendance summary directly
SUMMARY_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "
WITH ins AS (
  INSERT INTO attendance_summaries
    (tenant_id, employee_id, site_id, attendance_date,
     total_work_minutes, session_count, status,
     is_late, late_minutes, is_early_leave, early_leave_minutes,
     ot_minutes, missing_checkout)
  VALUES
    ('$TENANT_ID','$EMP_ID','$SITE_ID','2026-06-20',
     360, 1, 'present',
     TRUE, 15, FALSE, 0,
     0, FALSE)
  RETURNING id
)
SELECT id FROM ins;" | tr -d ' \n')

ADJUST_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/attendance/$SUMMARY_ID/adjust"
echo "Setup complete. TENANT=$TENANT_ID EMP=$EMP_ID SUMMARY=$SUMMARY_ID"
echo ""

# ── Test 1: Adjust totalWorkMinutes returns 200 ───────────────────────────────
echo "--- Test 1: Adjust totalWorkMinutes returns 200 ---"
run_test "Adjust totalWorkMinutes returns 200" 200 \
    -X PATCH "$ADJUST_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"totalWorkMinutes":480,"reason":"Employee worked extra 2 hours on site"}'

# ── Test 2: totalWorkMinutes updated in DB ────────────────────────────────────
echo ""
echo "--- Test 2: totalWorkMinutes updated in DB ---"
db_minutes=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT total_work_minutes FROM attendance_summaries WHERE id='$SUMMARY_ID';" | tr -d ' \n')
check_val "totalWorkMinutes=480" "$db_minutes" "480"

# ── Test 3: adjustment_reason stored in DB ────────────────────────────────────
echo ""
echo "--- Test 3: adjustment_reason stored ---"
db_reason=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT adjustment_reason FROM attendance_summaries WHERE id='$SUMMARY_ID';" | tr -d '\n' | xargs)
if echo "$db_reason" | grep -q "extra 2 hours"; then
    echo "PASS: adjustment_reason contains reason"
    PASS=$((PASS + 1))
else
    echo "FAIL: adjustment_reason='$db_reason'"
    FAIL=$((FAIL + 1))
fi

# ── Test 4: Response has updated totalWorkMinutes ─────────────────────────────
echo ""
echo "--- Test 4: Response body reflects updated value ---"
adj_body=$(curl -s -X PATCH "$ADJUST_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"totalWorkMinutes":500,"reason":"Correction after payroll review"}')
resp_minutes=$(echo "$adj_body" | grep -o '"totalWorkMinutes":[0-9]*' | head -1 | cut -d: -f2 || echo "")
check_val "Response totalWorkMinutes=500" "$resp_minutes" "500"

# ── Test 5: Adjust late flag ──────────────────────────────────────────────────
echo ""
echo "--- Test 5: Adjust isLate to false ---"
curl -s -o /dev/null -X PATCH "$ADJUST_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"late":false,"lateMinutes":0,"reason":"HR confirmed employee was on time"}'
db_late=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT is_late FROM attendance_summaries WHERE id='$SUMMARY_ID';" | tr -d ' \n')
check_val "is_late=false" "$db_late" "f"

# ── Test 6: Adjust status to incomplete ──────────────────────────────────────
echo ""
echo "--- Test 6: Adjust status to incomplete ---"
adj_status_body=$(curl -s -X PATCH "$ADJUST_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"incomplete","reason":"Session flagged for review"}')
resp_status=$(echo "$adj_status_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || echo "")
check_val "Response status=incomplete" "$resp_status" "incomplete"

# ── Test 7: Adjust missingCheckout flag ──────────────────────────────────────
echo ""
echo "--- Test 7: Clear missingCheckout flag ---"
curl -s -o /dev/null -X PATCH "$ADJUST_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"missingCheckout":false,"reason":"Employee confirmed checkout via phone"}'
db_missing=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT missing_checkout FROM attendance_summaries WHERE id='$SUMMARY_ID';" | tr -d ' \n')
check_val "missing_checkout=false" "$db_missing" "f"

# ── Test 8: Adjust otMinutes ─────────────────────────────────────────────────
echo ""
echo "--- Test 8: Add OT minutes ---"
curl -s -o /dev/null -X PATCH "$ADJUST_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"otMinutes":60,"reason":"Approved OT for emergency repair"}'
db_ot=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT ot_minutes FROM attendance_summaries WHERE id='$SUMMARY_ID';" | tr -d ' \n')
check_val "ot_minutes=60" "$db_ot" "60"

# ── Test 9: Response includes adjustmentReason ────────────────────────────────
echo ""
echo "--- Test 9: Response includes adjustmentReason field ---"
adj_r_body=$(curl -s -X PATCH "$ADJUST_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"totalWorkMinutes":420,"reason":"Final payroll correction"}')
has_reason=$(echo "$adj_r_body" | grep -c '"adjustmentReason"' || true)
check_val "adjustmentReason present in response" "$has_reason" "1"

# ── Test 10: Missing reason returns 400 ──────────────────────────────────────
echo ""
echo "--- Test 10: Missing reason returns 400 ---"
run_test "Missing reason returns 400" 400 \
    -X PATCH "$ADJUST_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"totalWorkMinutes":480}'

# ── Test 11: Negative totalWorkMinutes returns 400 ────────────────────────────
echo ""
echo "--- Test 11: Negative totalWorkMinutes returns 400 ---"
run_test "Negative totalWorkMinutes returns 400" 400 \
    -X PATCH "$ADJUST_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"totalWorkMinutes":-10,"reason":"Test"}'

# ── Test 12: Invalid status value returns 400 ─────────────────────────────────
echo ""
echo "--- Test 12: Invalid status value returns 400 ---"
run_test "Invalid status returns 400" 400 \
    -X PATCH "$ADJUST_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"absent","reason":"Test"}'

# ── Test 13: Non-existent summary returns 404 ─────────────────────────────────
echo ""
echo "--- Test 13: Non-existent summary returns 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000000"
run_test "Non-existent summary returns 404" 404 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/attendance/$FAKE_ID/adjust" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"totalWorkMinutes":480,"reason":"Test"}'

# ── Test 14: Cross-tenant summary returns 404 ─────────────────────────────────
echo ""
echo "--- Test 14: Cross-tenant summary returns 404 ---"
t2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Other Corp ${TS}\",\"slug\":\"otheradj-${TS}\"}")
T2_ID=$(echo "$t2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "Cross-tenant summary returns 404" 404 \
    -X PATCH "$BASE_URL/api/v1/tenants/$T2_ID/attendance/$SUMMARY_ID/adjust" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"totalWorkMinutes":480,"reason":"Test"}'

# ── Test 15: No permission returns 403 ────────────────────────────────────────
echo ""
echo "--- Test 15: No permission returns 403 ---"
noauth_email="adjust.noauth.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$noauth_email\",\"firstName\":\"No\",\"lastName\":\"Auth\"}"
noauth_inv=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$noauth_email' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$noauth_inv\",\"password\":\"Employee@1234\"}"
noauth_login=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$noauth_email\",\"password\":\"Employee@1234\"}")
NOAUTH_TOKEN=$(echo "$noauth_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "No permission returns 403" 403 \
    -X PATCH "$ADJUST_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $NOAUTH_TOKEN" \
    -d '{"totalWorkMinutes":480,"reason":"Unauthorized"}'

# ── Test 16: Partial update — only provided fields change ─────────────────────
echo ""
echo "--- Test 16: Partial update — unspecified fields unchanged ---"
# Set known state first
curl -s -o /dev/null -X PATCH "$ADJUST_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"totalWorkMinutes":300,"otMinutes":30,"reason":"Known state"}'
# Now update only otMinutes
curl -s -o /dev/null -X PATCH "$ADJUST_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"otMinutes":45,"reason":"Update only OT"}'
db_minutes_after=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT total_work_minutes FROM attendance_summaries WHERE id='$SUMMARY_ID';" | tr -d ' \n')
check_val "totalWorkMinutes unchanged after partial update" "$db_minutes_after" "300"

# ── Results ───────────────────────────────────────────────────────────────────
echo ""
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
