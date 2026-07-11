#!/usr/bin/env bash
# Tests for employee view of their own pending/sent random checks (task 101)
# GET /api/v1/tenants/{tenantId}/scheduled-checks/my-pending
# Usage: BASE_URL=http://localhost:8080 bash test_employee_pending_checks.sh

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

check_ge() {
    local name="$1" actual="$2" min="$3"
    if [ "${actual:-0}" -ge "$min" ]; then
        echo "PASS: $name ($actual >= $min)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected >= $min, got $actual"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Employee Pending Checks Tests (task 101) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"

# Login as platform admin
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

# Create tenant
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"PendingCheck Corp ${TS}\",\"slug\":\"pendingchk-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create site
s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Pending Site\",\"code\":\"PS-${TS}\",\"address\":\"1 Main St\",\"timezone\":\"UTC\"}")
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create shift
sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Day","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create employee via invitation — token is in the POST /invitations response body
EMP_EMAIL="pending.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"Pending\",\"lastName\":\"Emp\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation ($(echo "$inv_resp" | tail -n 1))"; exit 1; fi
INV_TOKEN=$(echo "$inv_resp" | head -n -1 | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$INV_TOKEN" ]; then echo "SETUP FAILED: could not extract invitation token from response"; exit 1; fi

# Accept invitation — creates employee + user account
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

# Get employee ID via list endpoint
emp_list=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/$TENANT_ID/employees?search=$EMP_EMAIL&size=5")
EMP_ID=$(echo "$emp_list" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$EMP_ID" ]; then echo "SETUP FAILED: could not find employee after accept"; exit 1; fi

# Login as the employee
emp_login=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
if [ "$(echo "$emp_login" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: employee login"; exit 1; fi
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

# Assign employee to site
asgn_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2099-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$asgn_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi

# Create random check config
cfg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/random-check-configs/tenant-default" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{
      "checksPerShift": 1,
      "minIntervalMinutes": 60,
      "allowedStartTime": "09:00:00",
      "allowedEndTime": "16:00:00",
      "checkMode": "location_only",
      "applicableRoles": [],
      "responseWindowSeconds": 3600
    }')
if [ "$(echo "$cfg_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: config"; exit 1; fi

# Trigger manual check (created with status='sent')
ch_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/manual" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"employeeId\":\"$EMP_ID\"}")
if [ "$(echo "$ch_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: manual check ($(echo "$ch_resp" | tail -n 1))"; exit 1; fi
CHECK_ID=$(echo "$ch_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create a second employee to test isolation
EMP2_EMAIL="pending.other.${TS}@example.com"
inv2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP2_EMAIL\",\"firstName\":\"Other\",\"lastName\":\"Emp\"}")
if [ "$(echo "$inv2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation emp2"; exit 1; fi
INV2_TOKEN=$(echo "$inv2_resp" | head -n -1 | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV2_TOKEN\",\"password\":\"Employee@1234\"}"
emp2_login=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMP2_EMAIL\",\"password\":\"Employee@1234\"}")
EMP2_TOKEN=$(echo "$emp2_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

MY_PENDING_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/scheduled-checks/my-pending"

echo "Setup complete:"
echo "  TENANT=$TENANT_ID"
echo "  SITE=$SITE_ID"
echo "  EMP=$EMP_ID"
echo "  CHECK=$CHECK_ID"
echo ""

# ── Test 1: GET /my-pending returns 200 for authenticated employee ─────────────
echo "--- Test 1: GET /my-pending returns 200 ---"
run_test "my-pending returns 200" 200 \
    -H "Authorization: Bearer $EMP_TOKEN" "$MY_PENDING_URL"

# ── Test 2: Response is an array ──────────────────────────────────────────────
echo ""
echo "--- Test 2: Response data is an array containing at least 1 check ---"
pending_body=$(curl -s -H "Authorization: Bearer $EMP_TOKEN" "$MY_PENDING_URL")
id_count=$(echo "$pending_body" | { grep -o '"id":"[^"]*"' || true; } | wc -l | tr -d ' \n')
check_ge "at least 1 check returned" "$id_count" "1"

# ── Test 3: The seeded check appears in the list ──────────────────────────────
echo ""
echo "--- Test 3: Seeded check ID appears in the list ---"
has_check=$(echo "$pending_body" | grep -c "\"$CHECK_ID\"" || true)
check_val "check ID found in response" "$has_check" "1"

# ── Test 4: secondsRemaining field is present ─────────────────────────────────
echo ""
echo "--- Test 4: secondsRemaining field is present ---"
has_seconds=$(echo "$pending_body" | grep -c '"secondsRemaining"' || true)
check_val "secondsRemaining field present" "$has_seconds" "1"

# ── Test 5: secondsRemaining is a number (not null) for sent checks ───────────
echo ""
echo "--- Test 5: secondsRemaining is a positive number (manual check has 1h window) ---"
seconds_val=$(echo "$pending_body" | grep -o '"secondsRemaining":[0-9-]*' | head -1 | cut -d: -f2)
if [ -n "$seconds_val" ] && [ "$seconds_val" -gt 0 ]; then
    echo "PASS: secondsRemaining=$seconds_val (>0)"
    PASS=$((PASS + 1))
else
    echo "FAIL: secondsRemaining=$seconds_val (expected >0 for fresh check)"
    FAIL=$((FAIL + 1))
fi

# ── Test 6: expiresAt field is present ───────────────────────────────────────
echo ""
echo "--- Test 6: expiresAt field is present ---"
has_expires=$(echo "$pending_body" | grep -c '"expiresAt"' || true)
check_val "expiresAt field present" "$has_expires" "1"

# ── Test 7: status field is 'sent' for dispatched manual check ────────────────
echo ""
echo "--- Test 7: status is 'sent' for dispatched manual check ---"
status_val=$(echo "$pending_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
check_val "status=sent for manual check" "$status_val" "sent"

# ── Test 8: employeeId in response matches our employee ───────────────────────
echo ""
echo "--- Test 8: employeeId in response matches our employee ---"
emp_in_resp=$(echo "$pending_body" | grep -o '"employeeId":"[^"]*"' | head -1 | cut -d'"' -f4)
check_val "employeeId matches" "$emp_in_resp" "$EMP_ID"

# ── Test 9: No auth token returns 401 ────────────────────────────────────────
echo ""
echo "--- Test 9: No auth token returns 401 ---"
run_test "No token returns 401" 401 \
    "$MY_PENDING_URL"

# ── Test 10: Employee 2 (no assignment/check) sees empty list ─────────────────
echo ""
echo "--- Test 10: Employee 2 with no checks sees empty array ---"
emp2_body=$(curl -s -H "Authorization: Bearer $EMP2_TOKEN" "$MY_PENDING_URL")
emp2_id_count=$(echo "$emp2_body" | { grep -o '"id":"[^"]*"' || true; } | wc -l | tr -d ' \n')
check_val "emp2 has 0 checks (isolation)" "$emp2_id_count" "0"

# ── Test 11: ?status=sent returns the sent check ─────────────────────────────
echo ""
echo "--- Test 11: ?status=sent returns the sent check ---"
sent_body=$(curl -s -H "Authorization: Bearer $EMP_TOKEN" "$MY_PENDING_URL?status=sent")
sent_id_count=$(echo "$sent_body" | { grep -o '"id":"[^"]*"' || true; } | wc -l | tr -d ' \n')
check_ge "status=sent returns >=1 check" "$sent_id_count" "1"

# ── Test 12: ?status=pending returns 0 (manual checks start as sent) ──────────
echo ""
echo "--- Test 12: ?status=pending returns 0 (manual checks are immediately sent) ---"
pending_only_body=$(curl -s -H "Authorization: Bearer $EMP_TOKEN" "$MY_PENDING_URL?status=pending")
pending_id_count=$(echo "$pending_only_body" | { grep -o '"id":"[^"]*"' || true; } | wc -l | tr -d ' \n')
check_val "status=pending returns 0 for manual-only checks" "$pending_id_count" "0"

# ── Test 13: Admin token also works (admin has an employee record too? No.) ───
# Admin is platform admin, not an employee in the tenant — expect 404
echo ""
echo "--- Test 13: Platform admin (non-employee) gets 404 from /my-pending ---"
run_test "Admin non-employee gets 404" 404 \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$MY_PENDING_URL"

# ── Test 14: Response contains siteId field ───────────────────────────────────
echo ""
echo "--- Test 14: Response contains siteId field ---"
has_site=$(echo "$pending_body" | grep -c '"siteId"' || true)
check_val "siteId present in response" "$has_site" "1"

# ── Test 15: siteId matches the created site ──────────────────────────────────
echo ""
echo "--- Test 15: siteId in response matches the assigned site ---"
site_in_resp=$(echo "$pending_body" | grep -o '"siteId":"[^"]*"' | head -1 | cut -d'"' -f4)
check_val "siteId matches" "$site_in_resp" "$SITE_ID"

# ── Results ───────────────────────────────────────────────────────────────────
echo ""
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
