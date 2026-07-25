#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/violations (HR list)
# Covers task 114 (HR views violation list with search, filter, sort, pagination)
# Usage: BASE_URL=http://localhost:8080 bash test_hr_list_violations.sh

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

echo "=== HR Violation List Tests (task 114) ==="
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
    -d "{\"name\":\"Violation List Corp ${TS}\",\"slug\":\"viol-list-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Invite and create an employee
INVITE_EMAIL="viol.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"Viol\",\"lastName\":\"Tester\"}")
if [ "$(echo "$inv_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: invitation"; exit 1; fi

INV_TOKEN=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT token FROM employee_invitations WHERE email='$INVITE_EMAIL' AND status='pending' LIMIT 1;" \
    | tr -d ' \n')
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}"

emp_login=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"password\":\"Employee@1234\"}")
if [ "$(echo "$emp_login" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: employee login"; exit 1; fi
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

EMP_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT e.id FROM employees e JOIN users u ON u.id=e.user_id WHERE u.email='$INVITE_EMAIL' AND e.deleted_at IS NULL LIMIT 1;" \
    | tr -d ' \n')

# Seed a violation directly in DB so we have data to query
SITE_ID=$(cat /proc/sys/kernel/random/uuid)
docker exec fams-postgres psql -U fams_user -d fams_db -c \
    "INSERT INTO violations (id, tenant_id, employee_id, site_id, violation_type, check_date, description, resolved, created_at)
     VALUES (uuid_generate_v4(), '$TENANT_ID', '$EMP_ID', '$SITE_ID', 'no_response', '2026-07-01', 'Test violation', false, now());" \
     > /dev/null

echo "Setup complete. TENANT_ID=$TENANT_ID  EMP_ID=$EMP_ID"
echo ""

VIOL_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/violations"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -s "$VIOL_URL"
echo ""

# ── Test 2: Employee token (no violations:list perm) → 403 ───────────────────
echo "--- Test 2: Employee without violations:list → 403 ---"
run_test "Employee forbidden" 403 -s \
    -H "Authorization: Bearer $EMP_TOKEN" "$VIOL_URL"
echo ""

# ── Test 3: Platform admin can list violations → 200 ─────────────────────────
echo "--- Test 3: Platform admin lists violations → 200 ---"
list_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$VIOL_URL")
list_body=$(echo "$list_resp" | head -n -1)
list_status=$(echo "$list_resp" | tail -n 1)
if [ "$list_status" -eq 200 ]; then
    total=$(echo "$list_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ -n "$total" ] && [ "$total" -ge 1 ]; then
        echo "PASS: Admin list returned $total records"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected >= 1 record, got totalElements=$total — $list_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $list_status — $list_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: Pagination envelope present ──────────────────────────────────────
echo "--- Test 4: Pagination fields present ---"
if [ "$list_status" -eq 200 ]; then
    if echo "$list_body" | grep -q '"page"' && echo "$list_body" | grep -q '"totalPages"' && \
       echo "$list_body" | grep -q '"totalElements"'; then
        echo "PASS: Pagination envelope present"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing pagination fields — $list_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Filter by employeeId → returns seeded record ─────────────────────
echo "--- Test 5: Filter by employeeId → seeded record returned ---"
emp_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$VIOL_URL?employeeId=$EMP_ID")
emp_body=$(echo "$emp_resp" | head -n -1)
emp_status=$(echo "$emp_resp" | tail -n 1)
if [ "$emp_status" -eq 200 ]; then
    total=$(echo "$emp_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "$total" -ge 1 ]; then
        echo "PASS: employeeId filter returned $total records"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected >= 1 record for employeeId filter, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $emp_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Filter by violationType=no_response ───────────────────────────────
echo "--- Test 6: Filter by violationType=no_response ---"
type_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$VIOL_URL?violationType=no_response")
type_body=$(echo "$type_resp" | head -n -1)
type_status=$(echo "$type_resp" | tail -n 1)
if [ "$type_status" -eq 200 ]; then
    total=$(echo "$type_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "$total" -ge 1 ]; then
        echo "PASS: violationType filter returned $total records"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected >= 1 record for violationType filter, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $type_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Filter by resolved=false → unresolved records ────────────────────
echo "--- Test 7: Filter by resolved=false ---"
run_test "resolved=false filter returns 200" 200 -s \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$VIOL_URL?resolved=false"
echo ""

# ── Test 8: Filter by resolved=true → 0 results (none resolved yet) ──────────
echo "--- Test 8: Filter by resolved=true → 0 results ---"
res_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$VIOL_URL?resolved=true")
res_body=$(echo "$res_resp" | head -n -1)
res_status=$(echo "$res_resp" | tail -n 1)
if [ "$res_status" -eq 200 ]; then
    total=$(echo "$res_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "$total" -eq 0 ]; then
        echo "PASS: resolved=true returns 0 records"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected 0 resolved records, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $res_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: Date range future → 0 results ────────────────────────────────────
echo "--- Test 9: from=2030-01-01 → 0 results ---"
future_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$VIOL_URL?from=2030-01-01")
future_body=$(echo "$future_resp" | head -n -1)
future_status=$(echo "$future_resp" | tail -n 1)
if [ "$future_status" -eq 200 ]; then
    total=$(echo "$future_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "$total" -eq 0 ]; then
        echo "PASS: Future date returns 0 records"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected 0 records for future date, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $future_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 10: sortBy=violationType&sortDir=asc returns 200 ────────────────────
echo "--- Test 10: sortBy=violationType&sortDir=asc → 200 ---"
run_test "Sort by violationType asc" 200 -s \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$VIOL_URL?sortBy=violationType&sortDir=asc"
echo ""

# ── Test 11: size=999 capped to 100 ──────────────────────────────────────────
echo "--- Test 11: size=999 capped to 100 ---"
big_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$VIOL_URL?size=999")
big_body=$(echo "$big_resp" | head -n -1)
big_status=$(echo "$big_resp" | tail -n 1)
if [ "$big_status" -eq 200 ]; then
    page_size=$(echo "$big_body" | grep -o '"size":[0-9]*' | head -1 | cut -d: -f2)
    if [ "$page_size" -le 100 ]; then
        echo "PASS: size capped to $page_size (<= 100)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: size=$page_size exceeds 100"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $big_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
