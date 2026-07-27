#!/usr/bin/env bash
# Tests for DELETE /api/v1/tenants/{tenantId}/sites/{siteId}/assignments/{assignmentId}
# Usage: BASE_URL=http://localhost:8080 bash test_cancel_assignment.sh

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

echo "=== Cancel Assignment Tests ==="
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

# ── Setup: tenant + site + employee + 2 assignments ───────────────────────────
echo "--- Setup: Create tenant, site, employees, assignments ---"
TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Cancel Corp ${TS}\",\"slug\":\"cancel-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"planId":"fc259250-bf91-4341-907e-00fa84587c38"}'  # bump trial->enterprise so site-limit (1) does not block multi-site tests

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"Main Site","timezone":"UTC"}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

e1_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Alice\",\"lastName\":\"N\",\"employeeCode\":\"A-${TS}\",\"email\":\"alice-${TS}@corp.com\"}")
if [ "$(echo "$e1_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: employee1"; exit 1; fi
EMP1=$(echo "$e1_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

e2_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Bob\",\"lastName\":\"T\",\"employeeCode\":\"B-${TS}\",\"email\":\"bob-${TS}@corp.com\"}")
if [ "$(echo "$e2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: employee2"; exit 1; fi
EMP2=$(echo "$e2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

ASSIGN_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments"

# Assignment A (to cancel)
a1_resp=$(curl -s -w "\n%{http_code}" -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP1\",\"startDate\":\"2026-07-01\",\"role\":\"worker\"}")
if [ "$(echo "$a1_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment A"; exit 1; fi
ASSIGN_A=$(echo "$a1_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Assignment B (for re-assign test after cancel)
a2_resp=$(curl -s -w "\n%{http_code}" -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP2\",\"startDate\":\"2026-07-01\",\"role\":\"worker\"}")
if [ "$(echo "$a2_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: assignment B"; exit 1; fi
ASSIGN_B=$(echo "$a2_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup: tenant=$TENANT_ID site=$SITE_ID emp1=$EMP1 emp2=$EMP2 assignA=$ASSIGN_A assignB=$ASSIGN_B"
echo ""

# ── Test 1: Cancel assignment (204) ──────────────────────────────────────────
echo "--- Test 1: Cancel assignment (204) ---"
run_test "Cancel assignment" 204 \
    -s -X DELETE "$ASSIGN_URL/$ASSIGN_A" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 2: Cancelled assignment has status=cancelled in list ─────────────────
echo "--- Test 2: Cancelled assignment appears with status=cancelled in list ---"
list_resp=$(curl -s -X GET "$ASSIGN_URL?status=cancelled" -H "Authorization: Bearer $ADMIN_TOKEN")
cancelled_count=$(echo "$list_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
if [ "$cancelled_count" = "1" ]; then
    echo "PASS: Cancelled assignment visible (status=cancelled count=$cancelled_count)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected 1 cancelled assignment, got $cancelled_count"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: activeAssignmentCount reflects cancellation ──────────────────────
echo "--- Test 3: activeAssignmentCount reduced after cancel ---"
detail=$(curl -s -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
count=$(echo "$detail" | grep -o '"activeAssignmentCount":[0-9]*' | cut -d: -f2)
if [ "$count" = "1" ]; then
    echo "PASS: activeAssignmentCount=$count (was 2, now 1 after cancel)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected activeAssignmentCount=1, got $count"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Employee can be re-assigned after cancellation (201) ──────────────
echo "--- Test 4: Employee can be re-assigned after cancel (201) ---"
run_test "Re-assign after cancel" 201 \
    -s -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP1\",\"startDate\":\"2026-10-01\",\"role\":\"supervisor\"}"
echo ""

# ── Test 5: Cancel already-cancelled assignment (400) ────────────────────────
echo "--- Test 5: Cancel already-cancelled assignment (400) ---"
run_test "Double cancel" 400 \
    -s -X DELETE "$ASSIGN_URL/$ASSIGN_A" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 6: Non-existent assignment (404) ────────────────────────────────────
echo "--- Test 6: Non-existent assignment (404) ---"
run_test "Non-existent assignment" 404 \
    -s -X DELETE "$ASSIGN_URL/00000000-0000-0000-0000-000000000000" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 7: Non-existent site (404) ──────────────────────────────────────────
echo "--- Test 7: Non-existent site (404) ---"
run_test "Non-existent site" 404 \
    -s -X DELETE "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/00000000-0000-0000-0000-000000000000/assignments/$ASSIGN_B" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 8: Non-existent tenant (404) ────────────────────────────────────────
echo "--- Test 8: Non-existent tenant (404) ---"
run_test "Non-existent tenant" 404 \
    -s -X DELETE "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/sites/$SITE_ID/assignments/$ASSIGN_B" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 9: Unauthenticated (401) ────────────────────────────────────────────
echo "--- Test 9: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X DELETE "$ASSIGN_URL/$ASSIGN_B"
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
