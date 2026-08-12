#!/usr/bin/env bash
# Tests for employee explanation submission (task 113)
# POST /api/v1/tenants/{tenantId}/checkin/{checkinId}/explain
# POST /api/v1/tenants/{tenantId}/violations/{violationId}/explain
# Usage: BASE_URL=http://localhost:8080 bash test_employee_explanation.sh

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

echo "=== Employee Explanation Tests (task 113) ==="
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
    -d "{\"name\":\"Explain Corp ${TS}\",\"slug\":\"explain-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Explain Site\",\"code\":\"ES-${TS}\",\"address\":\"1 St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"00:00","endTime":"23:59"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Employee 1 (the one who submits explanations)
EMP_EMAIL="explain.emp.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"Explain\",\"lastName\":\"Emp\"}"
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"
EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$EMP_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

# Employee 2 (different employee — to test cross-employee guard)
EMP2_EMAIL="explain.emp2.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP2_EMAIL\",\"firstName\":\"Other\",\"lastName\":\"Emp\"}"
INV2_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$EMP2_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV2_TOKEN\",\"password\":\"Employee@1234\"}"
emp2_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMP2_EMAIL\",\"password\":\"Employee@1234\"}")
EMP2_TOKEN=$(echo "$emp2_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi
ASGN_ID=$(echo "$asgn_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Seed a pending_review checkin for employee 1
CHECKIN_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "
WITH ins AS (
  INSERT INTO checkins
    (tenant_id, site_id, employee_id, assignment_id, shift_id, status,
     check_in_at, check_in_lat, check_in_lon, check_in_inside_geofence, gps_risk_score,
     check_out_at, work_minutes)
  VALUES
    ('$TENANT_ID','$SITE_ID','$EMP_ID','$ASGN_ID','$SHIFT_ID','pending_review',
     now() - interval '8 hours', 10.77, 106.70, FALSE, 0.8,
     now() - interval '2 hours', 360)
  RETURNING id
)
SELECT id FROM ins;" | tr -d ' \n')

# Seed a violation for employee 1
VIOLATION_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c "
WITH ins AS (
  INSERT INTO violations
    (tenant_id, employee_id, site_id, violation_type, check_date, description)
  VALUES
    ('$TENANT_ID','$EMP_ID','$SITE_ID','no_response', CURRENT_DATE, 'Missed random check')
  RETURNING id
)
SELECT id FROM ins;" | tr -d ' \n')

CHECKIN_EXPLAIN_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$CHECKIN_ID/explain"
VIOLATION_EXPLAIN_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/violations/$VIOLATION_ID/explain"
echo "Setup complete. TENANT=$TENANT_ID EMP=$EMP_ID CHECKIN=$CHECKIN_ID VIOLATION=$VIOLATION_ID"
echo ""

# ══════════════════════════════════════════════════════════════════════════════
# CHECK-IN EXPLANATION TESTS
# ══════════════════════════════════════════════════════════════════════════════

echo "=== Check-in explanation ==="
echo ""

# Small real PNG for the multipart photo tests — the JSON+photoUrl contract was retired
# (CheckinService.explainCheckin now rejects any request carrying photoUrl with 400, telling
# the caller to use multipart instead) — POST /{checkinId}/explain dispatches on Content-Type:
# application/json → note-only (CheckinController.explainCheckin), multipart/form-data →
# note+photo (CheckinController.explainCheckinWithPhoto, evidenceStorageService.store()).
EXPLAIN_PNG=$(mktemp /tmp/test_explain_XXXX.png)
python3 -c "
import struct, zlib
def chunk(tag, data):
    return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag+data))
raw = b''.join(b'\x00' + b'\xff\x00\x00' * 2 for _ in range(2))
ihdr = struct.pack('>IIBBBBB', 2, 2, 8, 2, 0, 0, 0)
open('$EXPLAIN_PNG','wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))
"

# ── Test 1: Employee submits explanation (multipart, note+photo) returns 200 ──
echo "--- Test 1: POST /{checkinId}/explain (multipart) returns 200 ---"
run_test "Submit checkin explanation returns 200" 200 \
    -X POST "$CHECKIN_EXPLAIN_URL" \
    -H "Authorization: Bearer $EMP_TOKEN" \
    -F "note=I was at the site entrance — GPS drifted outside the polygon" \
    -F "photo=@${EXPLAIN_PNG};type=image/png"

# ── Test 2: employee_note stored in DB ────────────────────────────────────────
echo ""
echo "--- Test 2: employee_note stored in DB ---"
db_note=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT employee_note FROM checkins WHERE id='$CHECKIN_ID';" | tr -d '\n' | xargs)
if echo "$db_note" | grep -q "GPS drifted"; then
    echo "PASS: employee_note stored correctly"
    PASS=$((PASS + 1))
else
    echo "FAIL: employee_note='$db_note'"
    FAIL=$((FAIL + 1))
fi

# ── Test 3: employee_photo_url (evidence marker) stored in DB ─────────────────
echo ""
echo "--- Test 3: employee_photo_url stored in DB ---"
db_photo=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT employee_photo_url FROM checkins WHERE id='$CHECKIN_ID';" | tr -d ' \n')
if [ -n "$db_photo" ]; then
    echo "PASS: employee_photo_url stored correctly ($db_photo)"
    PASS=$((PASS + 1))
else
    echo "FAIL: employee_photo_url is empty after multipart upload"
    FAIL=$((FAIL + 1))
fi

# ── Test 4: Response contains id and employeeNote ─────────────────────────────
echo ""
echo "--- Test 4: Response contains id and employeeNote ---"
exp_body=$(curl -s -X POST "$CHECKIN_EXPLAIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"note":"Updated explanation"}')
resp_id=$(echo "$exp_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || echo "")
check_val "Response id matches checkin" "$resp_id" "$CHECKIN_ID"

# ── Test 5: Explanation without photo URL works ───────────────────────────────
echo ""
echo "--- Test 5: Note without photoUrl returns 200 ---"
run_test "Note only (no photo) returns 200" 200 \
    -X POST "$CHECKIN_EXPLAIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"note":"No photo this time"}'

# ── Test 6: Missing note returns 400 ─────────────────────────────────────────
echo ""
echo "--- Test 6: Missing note returns 400 ---"
run_test "Missing note returns 400" 400 \
    -X POST "$CHECKIN_EXPLAIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"photoUrl":"https://cdn.example.com/photo.jpg"}'

# ── Test 7: Different employee cannot explain another's check-in ──────────────
echo ""
echo "--- Test 7: Different employee returns 403 ---"
run_test "Other employee returns 403 on checkin" 403 \
    -X POST "$CHECKIN_EXPLAIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_TOKEN" \
    -d '{"note":"Trying to explain someone elses checkin"}'

# ── Test 8: Non-existent check-in returns 404 ────────────────────────────────
echo ""
echo "--- Test 8: Non-existent check-in returns 404 ---"
FAKE_ID="00000000-0000-0000-0000-000000000000"
run_test "Non-existent checkin returns 404" 404 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/$FAKE_ID/explain" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"note":"Does not exist"}'

# ══════════════════════════════════════════════════════════════════════════════
# VIOLATION EXPLANATION TESTS
# ══════════════════════════════════════════════════════════════════════════════

echo ""
echo "=== Violation explanation ==="
echo ""

# ── Test 9: Employee submits violation explanation (multipart) returns 200 ────
echo "--- Test 9: POST /{violationId}/explain (multipart) returns 200 ---"
run_test "Submit violation explanation returns 200" 200 \
    -X POST "$VIOLATION_EXPLAIN_URL" \
    -H "Authorization: Bearer $EMP_TOKEN" \
    -F "note=My phone had no signal during the check window" \
    -F "photo=@${EXPLAIN_PNG};type=image/png"

# ── Test 10: employee_note stored in violations table ────────────────────────
echo ""
echo "--- Test 10: employee_note stored in violations table ---"
db_v_note=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT employee_note FROM violations WHERE id='$VIOLATION_ID';" | tr -d '\n' | xargs)
if echo "$db_v_note" | grep -q "no signal"; then
    echo "PASS: violation employee_note stored correctly"
    PASS=$((PASS + 1))
else
    echo "FAIL: violation employee_note='$db_v_note'"
    FAIL=$((FAIL + 1))
fi

# ── Test 11: employee_photo_url (evidence marker) stored in violations table ──
echo ""
echo "--- Test 11: employee_photo_url stored in violations table ---"
db_v_photo=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT employee_photo_url FROM violations WHERE id='$VIOLATION_ID';" | tr -d ' \n')
if [ -n "$db_v_photo" ]; then
    echo "PASS: violation employee_photo_url stored correctly ($db_v_photo)"
    PASS=$((PASS + 1))
else
    echo "FAIL: violation employee_photo_url is empty after multipart upload"
    FAIL=$((FAIL + 1))
fi

# ── Test 12: Violation response contains id ──────────────────────────────────
echo ""
echo "--- Test 12: Violation explanation response contains id ---"
v_exp_body=$(curl -s -X POST "$VIOLATION_EXPLAIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"note":"Second submission"}')
v_resp_id=$(echo "$v_exp_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || echo "")
check_val "Violation response id matches" "$v_resp_id" "$VIOLATION_ID"

# ── Test 13: Different employee cannot explain another's violation ─────────────
echo ""
echo "--- Test 13: Different employee returns 403 on violation ---"
run_test "Other employee returns 403 on violation" 403 \
    -X POST "$VIOLATION_EXPLAIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP2_TOKEN" \
    -d '{"note":"Trying to explain someone elses violation"}'

# ── Test 14: Missing note on violation returns 400 ───────────────────────────
echo ""
echo "--- Test 14: Missing note on violation returns 400 ---"
run_test "Missing note on violation returns 400" 400 \
    -X POST "$VIOLATION_EXPLAIN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{}'

# ── Test 15: Non-existent violation returns 404 ──────────────────────────────
echo ""
echo "--- Test 15: Non-existent violation returns 404 ---"
run_test "Non-existent violation returns 404" 404 \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/violations/$FAKE_ID/explain" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"note":"Does not exist"}'

# ── Test 16: Cross-tenant violation returns 404 ──────────────────────────────
echo ""
echo "--- Test 16: Cross-tenant violation returns 404 ---"
t2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Other Corp ${TS}\",\"slug\":\"otherexp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
T2_ID=$(echo "$t2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "Cross-tenant violation returns 404" 404 \
    -X POST "$BASE_URL/api/v1/tenants/$T2_ID/violations/$VIOLATION_ID/explain" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d '{"note":"Cross tenant attempt"}'

# ── Results ───────────────────────────────────────────────────────────────────
echo ""
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
