#!/usr/bin/env bash
# Tests for POST /api/v1/tenants/{tenantId}/sites/{siteId}/assignments
# Usage: BASE_URL=http://localhost:8080 bash test_create_assignment.sh

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

echo "=== Create Assignment Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: login ──────────────────────────────────────────────────────────────
echo "--- Setup: Login ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."

# ── Setup: tenant + site + shift + 2 employees ───────────────────────────────
echo "--- Setup: Create tenant, site, shift, employees ---"
TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Assign Corp ${TS}\",\"slug\":\"assign-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"planId":"fc259250-bf91-4341-907e-00fa84587c38"}'  # bump trial->enterprise so site-limit (1) does not block multi-site tests

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Main Site","timezone":"UTC"}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Morning Shift","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift"; exit 1; fi
SHIFT_ID=$(echo "$sh_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Employee A
e1_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Alice\",\"lastName\":\"Nguyen\",\"employeeCode\":\"EMP-A-${TS}\",\"email\":\"alice-${TS}@corp.com\"}")
if [ "$(echo "$e1_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: employee A"; exit 1; fi
EMP_A=$(echo "$e1_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Employee B
e2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Bob\",\"lastName\":\"Tran\",\"employeeCode\":\"EMP-B-${TS}\",\"email\":\"bob-${TS}@corp.com\"}")
if [ "$(echo "$e2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: employee B"; exit 1; fi
EMP_B=$(echo "$e2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

ASSIGN_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments"
echo "Setup: tenant=$TENANT_ID site=$SITE_ID shift=$SHIFT_ID empA=$EMP_A empB=$EMP_B"
echo ""

# ── Test 1: Create assignment with shift (201) ────────────────────────────────
echo "--- Test 1: Create assignment with shift (201) ---"
a1_resp=$(curl -s -w "\n%{http_code}" -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_A\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-07-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
a1_body=$(echo "$a1_resp" | head -n -1)
a1_status=$(echo "$a1_resp" | tail -n 1)
if [ "$a1_status" -eq 201 ]; then
    ASSIGN_A=$(echo "$a1_body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    a_role=$(echo "$a1_body" | grep -o '"role":"[^"]*"' | cut -d'"' -f4)
    a_stat=$(echo "$a1_body" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "PASS: Assignment with shift (HTTP 201, role=$a_role, status=$a_stat)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 201, got $a1_status body=$a1_body"
    ASSIGN_A=""
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Create assignment without shift (201) ─────────────────────────────
echo "--- Test 2: Assignment without shift (201) ---"
a2_resp=$(curl -s -w "\n%{http_code}" -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_B\",\"startDate\":\"2026-07-01\",\"role\":\"supervisor\"}")
a2_body=$(echo "$a2_resp" | head -n -1)
a2_status=$(echo "$a2_resp" | tail -n 1)
if [ "$a2_status" -eq 201 ]; then
    shift_val=$(echo "$a2_body" | grep -o '"shiftId":[^,}]*' | cut -d: -f2)
    echo "PASS: Assignment without shift (HTTP 201, shiftId=$shift_val)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 201, got $a2_status body=$a2_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Date fields serialised as yyyy-MM-dd ─────────────────────────────
echo "--- Test 3: startDate format is yyyy-MM-dd ---"
sd=$(echo "$a1_body" | grep -o '"startDate":"[^"]*"' | cut -d'"' -f4)
ed=$(echo "$a1_body" | grep -o '"endDate":"[^"]*"' | cut -d'"' -f4)
if echo "$sd" | grep -qE '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'; then
    echo "PASS: Date format correct (startDate=$sd, endDate=$ed)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Unexpected date format (startDate=$sd)"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: activeAssignmentCount reflected in site detail ───────────────────
echo "--- Test 4: activeAssignmentCount appears in site detail ---"
detail=$(curl -s -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
count=$(echo "$detail" | grep -o '"activeAssignmentCount":[0-9]*' | cut -d: -f2)
if [ "$count" = "2" ]; then
    echo "PASS: activeAssignmentCount=$count"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected activeAssignmentCount=2, got $count"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Duplicate active assignment rejected (409) ────────────────────────
echo "--- Test 5: Duplicate assignment for same employee+site (409) ---"
run_test "Duplicate assignment" 409 \
    -s -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_A\",\"startDate\":\"2026-08-01\"}"
echo ""

# ── Test 6: endDate before startDate (400) ────────────────────────────────────
echo "--- Test 6: endDate before startDate (400) ---"
run_test "endDate before startDate" 400 \
    -s -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_A\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-07-01\"}"
echo ""

# ── Test 7: Missing employeeId (400) ──────────────────────────────────────────
echo "--- Test 7: Missing employeeId (400) ---"
run_test "Missing employeeId" 400 \
    -s -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"startDate":"2026-07-01"}'
echo ""

# ── Test 8: Missing startDate (400) ───────────────────────────────────────────
echo "--- Test 8: Missing startDate (400) ---"
run_test "Missing startDate" 400 \
    -s -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_A\"}"
echo ""

# ── Test 9: Invalid role (400) ────────────────────────────────────────────────
echo "--- Test 9: Invalid role (400) ---"
run_test "Invalid role" 400 \
    -s -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_A\",\"startDate\":\"2026-07-01\",\"role\":\"manager\"}"
echo ""

# ── Test 10: Shift from different site rejected (404) ─────────────────────────
echo "--- Test 10: Shift belonging to different site (404) ---"
s2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Beta Site","timezone":"UTC"}')
SITE2_ID=$(echo "$s2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "Shift on wrong site" 404 \
    -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE2_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_A\",\"shiftId\":\"$SHIFT_ID\",\"startDate\":\"2026-07-01\"}"
echo ""

# ── Test 11: Non-existent employee (404) ──────────────────────────────────────
echo "--- Test 11: Non-existent employee (404) ---"
run_test "Non-existent employee" 404 \
    -s -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"employeeId":"00000000-0000-0000-0000-000000000000","startDate":"2026-07-01"}'
echo ""

# ── Test 12: Non-existent site (404) ──────────────────────────────────────────
echo "--- Test 12: Non-existent site (404) ---"
run_test "Non-existent site" 404 \
    -s -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/00000000-0000-0000-0000-000000000000/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_A\",\"startDate\":\"2026-07-01\"}"
echo ""

# ── Test 13: Non-existent tenant (404) ────────────────────────────────────────
echo "--- Test 13: Non-existent tenant (404) ---"
run_test "Non-existent tenant" 404 \
    -s -X POST "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_A\",\"startDate\":\"2026-07-01\"}"
echo ""

# ── Test 14: Unauthenticated (401) ────────────────────────────────────────────
echo "--- Test 14: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" \
    -d "{\"employeeId\":\"$EMP_A\",\"startDate\":\"2026-07-01\"}"
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
