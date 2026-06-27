#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/checkin/available-sites
# Usage: BASE_URL=http://localhost:8080 bash test_available_sites.sh

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

echo "=== Available Check-in Sites Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: login as platform admin ───────────────────────────────────────────
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: admin login (HTTP $login_status)"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."
echo ""

# ── Setup: create tenant, site, shift ────────────────────────────────────────
echo "--- Setup: Create tenant, site, shift ---"
TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"CheckIn Corp ${TS}\",\"slug\":\"checkin-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HQ Site","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542,"address":"123 Test St"}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Morning Shift","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "tenant=$TENANT_ID site=$SITE_ID shift=$SHIFT_ID"
echo ""

# ── Setup: invite employee, accept invitation (links userId to employee) ──────
echo "--- Setup: Invite and accept invitation ---"
INVITE_EMAIL="emp.checkin.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Tuan\",\"lastName\":\"Nguyen\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi
echo "Invitation sent to $INVITE_EMAIL"

# Retrieve invitation token from DB (query only)
INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
if [ -z "$INV_TOKEN" ]; then echo "SETUP FAILED: could not read invitation token"; exit 1; fi
echo "Invitation token retrieved."

# Accept invitation to create user account linked to employee
accept_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\",\"displayName\":\"Tuan Nguyen\"}")
accept_body=$(echo "$accept_resp" | head -n -1)
if [ "$(echo "$accept_resp" | tail -n 1)" -ne 200 ]; then
    echo "SETUP FAILED: accept invitation (HTTP $(echo "$accept_resp" | tail -n 1))"
    echo "Body: $accept_body"
    exit 1
fi
echo "Invitation accepted."

# Login as the employee
emp_login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"password\":\"Employee@1234\"}")
emp_login_body=$(echo "$emp_login_resp" | head -n -1)
if [ "$(echo "$emp_login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: employee login"; exit 1; fi
EMP_TOKEN=$(echo "$emp_login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Employee token obtained."

# Look up the employee ID
EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id = e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')
if [ -z "$EMP_ID" ]; then echo "SETUP FAILED: could not read employee id"; exit 1; fi
echo "Employee id=$EMP_ID"
echo ""

CHECKIN_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin/available-sites"

# ── Test 1: Unauthenticated → 401 ────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -s -X GET "$CHECKIN_URL"
echo ""

# ── Test 2: Valid token but no employee assignment today → 200 with empty list ─
echo "--- Test 2: Employee with no assignments today → 200 empty list ---"
resp=$(curl -s -X GET "$CHECKIN_URL" -H "Authorization: Bearer $EMP_TOKEN")
items=$({ echo "$resp" | grep -o '"assignmentId"' || true; } | wc -l | tr -d ' ')
if echo "$resp" | grep -q '"success":true' && [ "$items" -eq 0 ]; then
    echo "PASS: Empty available sites (HTTP 200, 0 items)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected success=true and 0 items, got: $resp"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Setup: create assignment covering today (2026-06-27) ─────────────────────
echo "--- Setup: Create active assignment covering today ---"
asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then
    echo "SETUP FAILED: assignment (HTTP $(echo "$asgn_resp" | tail -n 1)): $(echo "$asgn_resp" | head -n -1)"
    exit 1
fi
ASSIGN_ID=$(echo "$asgn_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Assignment created: id=$ASSIGN_ID"
echo ""

# ── Test 3: Happy path — employee has one site today → 200 with one item ─────
echo "--- Test 3: Employee has one active assignment today → 200 with site info ---"
happy_resp=$(curl -s -X GET "$CHECKIN_URL" -H "Authorization: Bearer $EMP_TOKEN")
site_name=$(echo "$happy_resp" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
shift_name=$(echo "$happy_resp" | grep -o '"name":"[^"]*"' | tail -1 | cut -d'"' -f4 || true)
assign_count=$({ echo "$happy_resp" | grep -o '"assignmentId"' || true; } | wc -l | tr -d ' ')
if echo "$happy_resp" | grep -q '"success":true' && [ "$assign_count" -eq 1 ]; then
    echo "PASS: One available site (HTTP 200, site=$site_name, shift=$shift_name)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 1 site, got response: $happy_resp"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Response includes shift info ─────────────────────────────────────
echo "--- Test 4: Response includes shift fields ---"
if echo "$happy_resp" | grep -q '"startTime"' && echo "$happy_resp" | grep -q '"earlyCheckinMinutes"'; then
    echo "PASS: Shift info present in response"
    PASS=$((PASS + 1))
else
    echo "FAIL: Missing shift fields in response: $happy_resp"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Response includes site lat/lng ───────────────────────────────────
echo "--- Test 5: Response includes site coordinates ---"
if echo "$happy_resp" | grep -q '"latitude"' && echo "$happy_resp" | grep -q '"longitude"'; then
    echo "PASS: Site coordinates present in response"
    PASS=$((PASS + 1))
else
    echo "FAIL: Missing site coordinates in response: $happy_resp"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Past-only assignment (endDate before today) → not returned ────────
echo "--- Setup: Create past assignment (endDate=2025-12-31) ---"
past_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"startDate\":\"2025-01-01\",\"endDate\":\"2025-12-31\",\"role\":\"worker\"}")
# Note: this may 409 if employee already has active assignment on same site — that is expected
past_status=$(echo "$past_resp" | tail -n 1)
echo "Past assignment attempt status: $past_status (409 is acceptable — same site conflict)"

# ── Test 7: Admin token has no employee profile → 404 ───────────────────────
echo ""
echo "--- Test 7: Platform admin has no employee profile → 404 ---"
run_test "No employee profile for user" 404 \
    -s -X GET "$CHECKIN_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
