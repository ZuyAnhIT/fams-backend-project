#!/usr/bin/env bash
# Tests for PUT /api/v1/tenants/{tenantId}/sites/{siteId}/assignments/{assignmentId}
# Usage: BASE_URL=http://localhost:8080 bash test_update_assignment.sh

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

echo "=== Update Assignment Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: login ──────────────────────────────────────────────────────────────
echo "--- Setup: Login ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Admin token obtained."

# ── Setup: tenant + site + 2 shifts + employee + assignment ───────────────────
echo "--- Setup: Create tenant, site, shifts, employee, assignment ---"
TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Upd Corp ${TS}\",\"slug\":\"upd-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Main Site","timezone":"UTC"}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh1_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Morning","startTime":"08:00","endTime":"17:00"}')
if [ "$(echo "$sh1_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift1"; exit 1; fi
SHIFT1_ID=$(echo "$sh1_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

sh2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Evening","startTime":"17:00","endTime":"23:00"}')
if [ "$(echo "$sh2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: shift2"; exit 1; fi
SHIFT2_ID=$(echo "$sh2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

e_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Alice\",\"lastName\":\"N\",\"employeeCode\":\"A-${TS}\",\"email\":\"alice-${TS}@corp.com\"}")
if [ "$(echo "$e_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: employee"; exit 1; fi
EMP_ID=$(echo "$e_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

ASSIGN_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments"

a_resp=$(curl -s -w "\n%{http_code}" -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"shiftId\":\"$SHIFT1_ID\",\"startDate\":\"2026-07-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}")
if [ "$(echo "$a_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment"; exit 1; fi
ASSIGN_ID=$(echo "$a_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup: tenant=$TENANT_ID site=$SITE_ID shift1=$SHIFT1_ID shift2=$SHIFT2_ID emp=$EMP_ID assign=$ASSIGN_ID"
echo ""

ASSIGN_ITEM_URL="$ASSIGN_URL/$ASSIGN_ID"

# ── Test 1: Update role (200) ─────────────────────────────────────────────────
echo "--- Test 1: Update role to supervisor (200) ---"
u1_resp=$(curl -s -w "\n%{http_code}" -X PUT "$ASSIGN_ITEM_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"role":"supervisor"}')
u1_body=$(echo "$u1_resp" | head -n -1)
u1_status=$(echo "$u1_resp" | tail -n 1)
u1_role=$(echo "$u1_body" | grep -o '"role":"[^"]*"' | cut -d'"' -f4)
if [ "$u1_status" -eq 200 ] && [ "$u1_role" = "supervisor" ]; then
    echo "PASS: Role updated to supervisor (HTTP 200)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 200 role=supervisor, got $u1_status role=$u1_role body=$u1_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Update shiftId (200) ──────────────────────────────────────────────
echo "--- Test 2: Update shiftId to SHIFT2 (200) ---"
u2_resp=$(curl -s -w "\n%{http_code}" -X PUT "$ASSIGN_ITEM_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"shiftId\":\"$SHIFT2_ID\"}")
u2_body=$(echo "$u2_resp" | head -n -1)
u2_status=$(echo "$u2_resp" | tail -n 1)
u2_shift=$(echo "$u2_body" | grep -o '"shiftId":"[^"]*"' | cut -d'"' -f4)
if [ "$u2_status" -eq 200 ] && [ "$u2_shift" = "$SHIFT2_ID" ]; then
    echo "PASS: shiftId updated (HTTP 200, shiftId=$u2_shift)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 200 shiftId=$SHIFT2_ID, got $u2_status shiftId=$u2_shift"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Clear shiftId (200) ───────────────────────────────────────────────
echo "--- Test 3: Clear shiftId (clearShift=true, 200) ---"
u3_resp=$(curl -s -w "\n%{http_code}" -X PUT "$ASSIGN_ITEM_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"clearShift":true}')
u3_body=$(echo "$u3_resp" | head -n -1)
u3_status=$(echo "$u3_resp" | tail -n 1)
u3_shift=$(echo "$u3_body" | grep -o '"shiftId":[^,}]*' | cut -d: -f2)
if [ "$u3_status" -eq 200 ] && [ "$u3_shift" = "null" ]; then
    echo "PASS: shiftId cleared (HTTP 200, shiftId=null)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 200 shiftId=null, got $u3_status shiftId=$u3_shift"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Update startDate (200) ────────────────────────────────────────────
echo "--- Test 4: Update startDate (200) ---"
u4_resp=$(curl -s -w "\n%{http_code}" -X PUT "$ASSIGN_ITEM_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"startDate":"2026-08-01"}')
u4_body=$(echo "$u4_resp" | head -n -1)
u4_status=$(echo "$u4_resp" | tail -n 1)
u4_sd=$(echo "$u4_body" | grep -o '"startDate":"[^"]*"' | cut -d'"' -f4)
if [ "$u4_status" -eq 200 ] && [ "$u4_sd" = "2026-08-01" ]; then
    echo "PASS: startDate updated (HTTP 200, startDate=$u4_sd)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 200 startDate=2026-08-01, got $u4_status startDate=$u4_sd"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Clear endDate (200) ───────────────────────────────────────────────
echo "--- Test 5: Clear endDate (clearEndDate=true, 200) ---"
u5_resp=$(curl -s -w "\n%{http_code}" -X PUT "$ASSIGN_ITEM_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"clearEndDate":true}')
u5_body=$(echo "$u5_resp" | head -n -1)
u5_status=$(echo "$u5_resp" | tail -n 1)
u5_ed=$(echo "$u5_body" | grep -o '"endDate":[^,}]*' | cut -d: -f2)
if [ "$u5_status" -eq 200 ] && [ "$u5_ed" = "null" ]; then
    echo "PASS: endDate cleared (HTTP 200, endDate=null)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 200 endDate=null, got $u5_status endDate=$u5_ed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Update notes (200) ────────────────────────────────────────────────
echo "--- Test 6: Update notes (200) ---"
u6_resp=$(curl -s -w "\n%{http_code}" -X PUT "$ASSIGN_ITEM_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"notes":"Moved to evening shift"}')
u6_status=$(echo "$u6_resp" | tail -n 1)
u6_notes=$(echo "$u6_resp" | head -n -1 | grep -o '"notes":"[^"]*"' | cut -d'"' -f4)
if [ "$u6_status" -eq 200 ] && [ "$u6_notes" = "Moved to evening shift" ]; then
    echo "PASS: Notes updated (HTTP 200)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 200 with notes, got $u6_status notes=$u6_notes"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: endDate before startDate (400) ────────────────────────────────────
echo "--- Test 7: endDate before startDate (400) ---"
run_test "endDate before startDate" 400 \
    -s -X PUT "$ASSIGN_ITEM_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"startDate":"2026-12-01","endDate":"2026-07-01"}'
echo ""

# ── Test 8: Invalid role (400) ────────────────────────────────────────────────
echo "--- Test 8: Invalid role (400) ---"
run_test "Invalid role" 400 \
    -s -X PUT "$ASSIGN_ITEM_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"role":"manager"}'
echo ""

# ── Test 9: Shift from different site (404) ───────────────────────────────────
echo "--- Test 9: Shift from a different site (404) ---"
s2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Beta Site","timezone":"UTC"}')
SITE2_ID=$(echo "$s2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
sh3_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE2_ID/shifts" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Night","startTime":"22:00","endTime":"06:00","allowOvernight":true}')
SHIFT3_ID=$(echo "$sh3_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "Shift on wrong site" 404 \
    -s -X PUT "$ASSIGN_ITEM_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"shiftId\":\"$SHIFT3_ID\"}"
echo ""

# ── Test 10: Non-existent assignment (404) ────────────────────────────────────
echo "--- Test 10: Non-existent assignment (404) ---"
run_test "Non-existent assignment" 404 \
    -s -X PUT "$ASSIGN_URL/00000000-0000-0000-0000-000000000000" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"role":"worker"}'
echo ""

# ── Test 11: Non-existent site (404) ─────────────────────────────────────────
echo "--- Test 11: Non-existent site (404) ---"
run_test "Non-existent site" 404 \
    -s -X PUT "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/00000000-0000-0000-0000-000000000000/assignments/$ASSIGN_ID" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"role":"worker"}'
echo ""

# ── Test 12: Unauthenticated (401) ───────────────────────────────────────────
echo "--- Test 12: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X PUT "$ASSIGN_ITEM_URL" \
    -H "Content-Type: application/json" \
    -d '{"role":"worker"}'
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
