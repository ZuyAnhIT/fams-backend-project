#!/usr/bin/env bash
# Tests for HR override check-in (task 111)
# PATCH /api/v1/tenants/{tenantId}/checkin/{checkinId}/override
# Usage: BASE_URL=http://localhost:8080 bash test_override_checkin.sh

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

echo "=== HR Override Check-in Tests (task 111) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Override Corp ${TS}\",\"slug\":\"override-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Override Site\",\"code\":\"OS-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_EMAIL="override.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"Override\",\"lastName\":\"Emp\"}"
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"
EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$EMP_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi
ASGN_ID=$(echo "$asgn_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Seed a pending_review checkin directly in DB (with checkout to avoid open-session constraint)
CHECKIN_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "
WITH ins AS (
  INSERT INTO checkins
    (tenant_id, site_id, employee_id, assignment_id, shift_id, status,
     check_in_at, check_in_lat, check_in_lon, check_in_inside_geofence, gps_risk_score,
     check_out_at, work_minutes)
  VALUES
    ('$TENANT_ID','$SITE_ID','$EMP_ID','$ASGN_ID','$SHIFT_ID','pending_review',
     now() - interval '8 hours', 10.77, 106.70, FALSE, 0.8,
     now() - interval '6 hours', 120)
  RETURNING id
)
SELECT id FROM ins;" | tr -d ' \n')

# Seed a valid checkin for rejection test (with checkout so no open-session conflict)
VALID_CHECKIN_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "
WITH ins AS (
  INSERT INTO checkins
    (tenant_id, site_id, employee_id, assignment_id, shift_id, status,
     check_in_at, check_in_lat, check_in_lon, check_in_inside_geofence, gps_risk_score,
     check_out_at, work_minutes)
  VALUES
    ('$TENANT_ID','$SITE_ID','$EMP_ID','$ASGN_ID','$SHIFT_ID','valid',
     now() - interval '10 hours', 10.77, 106.70, TRUE, 0.0,
     now() - interval '2 hours', 480)
  RETURNING id
)
SELECT id FROM ins;" | tr -d ' \n')

OVERRIDE_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN_ID/override"
echo "Setup complete. TENANT=$TENANT_ID EMP=$EMP_ID CHECKIN=$CHECKIN_ID VALID_CHECKIN=$VALID_CHECKIN_ID"
echo ""

# ── Test 1: Override pending_review → valid returns 200 ───────────────────────
echo "--- Test 1: Override pending_review → valid returns 200 ---"
run_test "Override to valid returns 200" 200 \
    -X PATCH "$OVERRIDE_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"valid","reason":"Employee confirmed on site via phone call"}'

# ── Test 2: Status updated to valid in DB ─────────────────────────────────────
echo ""
echo "--- Test 2: Status updated to valid in DB ---"
db_status=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT status FROM checkins WHERE id='$CHECKIN_ID';" | tr -d ' \n')
check_val "DB status=valid" "$db_status" "valid"

# ── Test 3: Note updated with reason ──────────────────────────────────────────
echo ""
echo "--- Test 3: Note updated with override reason ---"
db_note=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT note FROM checkins WHERE id='$CHECKIN_ID';" | tr -d '\n' | xargs)
if echo "$db_note" | grep -q "phone call"; then
    echo "PASS: note contains reason"
    PASS=$((PASS + 1))
else
    echo "FAIL: note='$db_note' (expected reason in note)"
    FAIL=$((FAIL + 1))
fi

# ── Test 4: Response body contains updated status ─────────────────────────────
echo ""
echo "--- Test 4: Response body has status=valid ---"
# Re-seed a pending_review for further tests
CHECKIN2_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "
WITH ins AS (
  INSERT INTO checkins
    (tenant_id, site_id, employee_id, assignment_id, shift_id, status,
     check_in_at, check_in_lat, check_in_lon, check_in_inside_geofence, gps_risk_score,
     check_out_at, work_minutes)
  VALUES
    ('$TENANT_ID','$SITE_ID','$EMP_ID','$ASGN_ID','$SHIFT_ID','pending_review',
     now() - interval '12 hours', 10.77, 106.70, FALSE, 0.7,
     now() - interval '9 hours', 180)
  RETURNING id
)
SELECT id FROM ins;" | tr -d ' \n')
override_body=$(curl -s \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN2_ID/override" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"rejected","reason":"Location does not match site records"}')
resp_status=$(echo "$override_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || echo "")
check_val "Response status=rejected" "$resp_status" "rejected"

# ── Test 5: Override valid → rejected returns 200 ─────────────────────────────
echo ""
echo "--- Test 5: Override valid → rejected returns 200 ---"
run_test "Override valid to rejected returns 200" 200 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$VALID_CHECKIN_ID/override" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"rejected","reason":"Duplicate record identified"}'

# ── Test 6: Override rejected → valid (reverse) returns 200 ───────────────────
echo ""
echo "--- Test 6: Reverse rejection (rejected → valid) returns 200 ---"
run_test "Reverse rejection returns 200" 200 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$VALID_CHECKIN_ID/override" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"valid","reason":"Reverting — duplicate was a mistake"}'

# ── Test 7: Override to same status returns 400 ───────────────────────────────
echo ""
echo "--- Test 7: Override to same status returns 400 ---"
run_test "Same status override returns 400" 400 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$VALID_CHECKIN_ID/override" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"valid","reason":"Already valid"}'

# ── Test 8: Missing status returns 400 ───────────────────────────────────────
echo ""
echo "--- Test 8: Missing status returns 400 ---"
run_test "Missing status returns 400" 400 \
    -X PATCH "$OVERRIDE_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"reason":"No status field"}'

# ── Test 9: Invalid status value returns 400 ─────────────────────────────────
echo ""
echo "--- Test 9: Invalid status value returns 400 ---"
run_test "Invalid status value returns 400" 400 \
    -X PATCH "$OVERRIDE_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"pending_review","reason":"Not allowed"}'

# ── Test 10: Missing reason returns 400 ──────────────────────────────────────
echo ""
echo "--- Test 10: Missing reason returns 400 ---"
run_test "Missing reason returns 400" 400 \
    -X PATCH "$OVERRIDE_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"valid"}'

# ── Test 11: Non-existent check-in returns 404 ───────────────────────────────
echo ""
echo "--- Test 11: Non-existent check-in returns 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000000"
run_test "Non-existent check-in returns 404" 404 \
    -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$FAKE_ID/override" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"valid","reason":"Test"}'

# ── Test 12: Cross-tenant check-in returns 404 ───────────────────────────────
echo ""
echo "--- Test 12: Cross-tenant check-in returns 404 ---"
t2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Other Corp ${TS}\",\"slug\":\"otherover-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
T2_ID=$(echo "$t2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "Cross-tenant check-in returns 404" 404 \
    -X PATCH "$BASE_URL/api/v1/tenants/$T2_ID/checkin/$CHECKIN_ID/override" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"valid","reason":"Test"}'

# ── Test 13: No permission returns 403 ───────────────────────────────────────
echo ""
echo "--- Test 13: No permission returns 403 ---"
noauth_email="override.noauth.${TS}@example.com"
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
    -d "{\"identifier\":\"$noauth_email\",\"password\":\"Employee@1234\"}")
NOAUTH_TOKEN=$(echo "$noauth_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "No permission returns 403" 403 \
    -X PATCH "$OVERRIDE_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $NOAUTH_TOKEN" \
    -d '{"status":"valid","reason":"Unauthorized attempt"}'

# ── Test 14: updatedAt changed after override ────────────────────────────────
echo ""
echo "--- Test 14: updatedAt changed after override ---"
CHECKIN3_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "
WITH ins AS (
  INSERT INTO checkins
    (tenant_id, site_id, employee_id, assignment_id, shift_id, status,
     check_in_at, check_in_lat, check_in_lon, check_in_inside_geofence, gps_risk_score,
     check_out_at, work_minutes)
  VALUES
    ('$TENANT_ID','$SITE_ID','$EMP_ID','$ASGN_ID','$SHIFT_ID','pending_review',
     now() - interval '14 hours', 10.77, 106.70, FALSE, 0.6,
     now() - interval '11 hours', 180)
  RETURNING id
)
SELECT id FROM ins;" | tr -d ' \n')
before_updated=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT updated_at FROM checkins WHERE id='$CHECKIN3_ID';" | tr -d ' \n')
sleep 1
curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN3_ID/override" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"status":"valid","reason":"Confirmed"}'
after_updated=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT updated_at FROM checkins WHERE id='$CHECKIN3_ID';" | tr -d ' \n')
if [ "$before_updated" != "$after_updated" ]; then
    echo "PASS: updatedAt changed after override"
    PASS=$((PASS + 1))
else
    echo "FAIL: updatedAt did not change (before=$before_updated after=$after_updated)"
    FAIL=$((FAIL + 1))
fi

# ── Results ───────────────────────────────────────────────────────────────────
echo ""
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
