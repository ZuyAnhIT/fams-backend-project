#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/sites/{siteId}/assignments
# Usage: BASE_URL=http://localhost:8080 bash test_list_assignments.sh

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

echo "=== List Assignments Tests ==="
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

# ── Setup: tenant + site + 2 shifts + 3 employees + 3 assignments ─────────────
echo "--- Setup: Create tenant, site, shifts, employees, assignments ---"
TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"List Corp ${TS}\",\"slug\":\"list-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/tenants/$TENANT_ID/subscription" -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"planId":"fc259250-bf91-4341-907e-00fa84587c38"}'  # bump trial->enterprise so site-limit (1) does not block multi-site tests

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

e3_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/employees" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"firstName\":\"Carol\",\"lastName\":\"L\",\"employeeCode\":\"C-${TS}\",\"email\":\"carol-${TS}@corp.com\"}")
if [ "$(echo "$e3_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: employee3"; exit 1; fi
EMP3=$(echo "$e3_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

ASSIGN_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments"

# Alice: worker, shift1
curl -s -o /dev/null -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP1\",\"shiftId\":\"$SHIFT1_ID\",\"startDate\":\"2026-07-01\",\"role\":\"worker\"}"

# Bob: supervisor, shift2
curl -s -o /dev/null -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP2\",\"shiftId\":\"$SHIFT2_ID\",\"startDate\":\"2026-08-01\",\"role\":\"supervisor\"}"

# Carol: worker, no shift
curl -s -o /dev/null -X POST "$ASSIGN_URL" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP3\",\"startDate\":\"2026-09-01\",\"role\":\"worker\"}"

echo "Setup: tenant=$TENANT_ID site=$SITE_ID emp1=$EMP1 emp2=$EMP2 emp3=$EMP3"
echo ""

# ── Test 1: List all assignments (200, 3 items) ───────────────────────────────
echo "--- Test 1: List all assignments (200, 3 items) ---"
list_resp=$(curl -s -X GET "$ASSIGN_URL" -H "Authorization: Bearer $ADMIN_TOKEN")
total=$(echo "$list_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
if [ "$total" = "3" ]; then
    echo "PASS: List all (HTTP 200, totalElements=$total)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected totalElements=3, got: $total (body=$list_resp)"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 2: Filter by role=worker (2 items) ───────────────────────────────────
echo "--- Test 2: Filter by role=worker (2 items) ---"
role_resp=$(curl -s -X GET "$ASSIGN_URL?role=worker" -H "Authorization: Bearer $ADMIN_TOKEN")
role_total=$(echo "$role_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
if [ "$role_total" = "2" ]; then
    echo "PASS: role=worker filter (totalElements=$role_total)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected totalElements=2 for role=worker, got $role_total"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Filter by role=supervisor (1 item) ───────────────────────────────
echo "--- Test 3: Filter by role=supervisor (1 item) ---"
sup_resp=$(curl -s -X GET "$ASSIGN_URL?role=supervisor" -H "Authorization: Bearer $ADMIN_TOKEN")
sup_total=$(echo "$sup_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
if [ "$sup_total" = "1" ]; then
    echo "PASS: role=supervisor filter (totalElements=$sup_total)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected totalElements=1 for role=supervisor, got $sup_total"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Filter by status=active (3 items) ────────────────────────────────
echo "--- Test 4: Filter by status=active (3 items) ---"
stat_resp=$(curl -s -X GET "$ASSIGN_URL?status=active" -H "Authorization: Bearer $ADMIN_TOKEN")
stat_total=$(echo "$stat_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
if [ "$stat_total" = "3" ]; then
    echo "PASS: status=active filter (totalElements=$stat_total)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected totalElements=3 for status=active, got $stat_total"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Filter by employeeId (1 item) ────────────────────────────────────
echo "--- Test 5: Filter by employeeId=EMP2 (1 item) ---"
emp_resp=$(curl -s -X GET "$ASSIGN_URL?employeeId=$EMP2" -H "Authorization: Bearer $ADMIN_TOKEN")
emp_total=$(echo "$emp_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
if [ "$emp_total" = "1" ]; then
    echo "PASS: employeeId filter (totalElements=$emp_total)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected totalElements=1 for employeeId=$EMP2, got $emp_total"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Filter by shiftId (1 item for shift1) ────────────────────────────
echo "--- Test 6: Filter by shiftId=SHIFT1 (1 item) ---"
sh_resp=$(curl -s -X GET "$ASSIGN_URL?shiftId=$SHIFT1_ID" -H "Authorization: Bearer $ADMIN_TOKEN")
sh_total=$(echo "$sh_resp" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
if [ "$sh_total" = "1" ]; then
    echo "PASS: shiftId filter (totalElements=$sh_total)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected totalElements=1 for shiftId=$SHIFT1_ID, got $sh_total"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Default sort is startDate ASC (Alice first, then Bob, then Carol) ─
echo "--- Test 7: Default sort startDate ASC ---"
sorted_resp=$(curl -s -X GET "$ASSIGN_URL?sortBy=startDate&sortDir=asc" -H "Authorization: Bearer $ADMIN_TOKEN")
first_emp=$(echo "$sorted_resp" | grep -o '"employeeId":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ "$first_emp" = "$EMP1" ]; then
    echo "PASS: startDate ASC — first is EMP1 (Alice, 2026-07-01)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected first employeeId=$EMP1, got $first_emp"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Sort startDate DESC (Carol first) ────────────────────────────────
echo "--- Test 8: Sort startDate DESC ---"
desc_resp=$(curl -s -X GET "$ASSIGN_URL?sortBy=startDate&sortDir=desc" -H "Authorization: Bearer $ADMIN_TOKEN")
first_emp_desc=$(echo "$desc_resp" | grep -o '"employeeId":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ "$first_emp_desc" = "$EMP3" ]; then
    echo "PASS: startDate DESC — first is EMP3 (Carol, 2026-09-01)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected first employeeId=$EMP3, got $first_emp_desc"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: Pagination (size=2, page=0 → 2 items, totalPages=2) ──────────────
echo "--- Test 9: Pagination size=2 page=0 ---"
page_resp=$(curl -s -X GET "$ASSIGN_URL?size=2&page=0" -H "Authorization: Bearer $ADMIN_TOKEN")
page_size=$(echo "$page_resp" | grep -o '"size":[0-9]*' | head -1 | cut -d: -f2)
total_pages=$(echo "$page_resp" | grep -o '"totalPages":[0-9]*' | cut -d: -f2)
if [ "$page_size" = "2" ] && [ "$total_pages" = "2" ]; then
    echo "PASS: Pagination (size=$page_size, totalPages=$total_pages)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected size=2 totalPages=2, got size=$page_size totalPages=$total_pages"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 10: PageResponse has required fields ─────────────────────────────────
echo "--- Test 10: PageResponse fields (totalElements, totalPages, first, last) ---"
fields_resp=$(curl -s -X GET "$ASSIGN_URL" -H "Authorization: Bearer $ADMIN_TOKEN")
has_total=$(echo "$fields_resp" | grep -c '"totalElements"')
has_pages=$(echo "$fields_resp" | grep -c '"totalPages"')
has_first=$(echo "$fields_resp" | grep -c '"first"')
has_last=$(echo "$fields_resp" | grep -c '"last"')
if [ "$has_total" -ge 1 ] && [ "$has_pages" -ge 1 ] && [ "$has_first" -ge 1 ] && [ "$has_last" -ge 1 ]; then
    echo "PASS: All PageResponse fields present"
    PASS=$((PASS + 1))
else
    echo "FAIL: Missing PageResponse fields (totalElements=$has_total totalPages=$has_pages first=$has_first last=$has_last)"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 11: Non-existent site (404) ─────────────────────────────────────────
echo "--- Test 11: Non-existent site (404) ---"
run_test "Non-existent site" 404 \
    -s -X GET "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/00000000-0000-0000-0000-000000000000/assignments" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 12: Non-existent tenant (404) ───────────────────────────────────────
echo "--- Test 12: Non-existent tenant (404) ---"
run_test "Non-existent tenant" 404 \
    -s -X GET "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/sites/$SITE_ID/assignments" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 13: Unauthenticated (401) ───────────────────────────────────────────
echo "--- Test 13: No token (401) ---"
run_test "Unauthenticated" 401 \
    -s -X GET "$ASSIGN_URL"
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"

[ "$FAIL" -eq 0 ]
