#!/usr/bin/env bash
# Tests for GET /api/v1/tenants/{tenantId}/checkin (HR list)
# Covers task 78 (HR views all check-ins with filter, sort, pagination)
# Usage: BASE_URL=http://localhost:8080 bash test_hr_list_checkins.sh

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

echo "=== HR Check-in List Tests (task 78) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup ─────────────────────────────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
if [ "$(echo "$login_resp" | tail -n 1)" -ne 200 ]; then echo "SETUP FAILED: admin login"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

TS=$(date +%s)

t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"HR List Corp ${TS}\",\"slug\":\"hr-list-corp-${TS}\"}")
if [ "$(echo "$t_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: tenant"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

s_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"name":"HQ","timezone":"Asia/Ho_Chi_Minh","latitude":21.0285,"longitude":105.8542}')
if [ "$(echo "$s_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: site"; exit 1; fi
SITE_ID=$(echo "$s_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# Create employee and check-in
INVITE_EMAIL="hr.emp.${TS}@example.com"
inv_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$INVITE_EMAIL\",\"firstName\":\"HR\",\"lastName\":\"Tester\"}")
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

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/sites/$SITE_ID/assignments" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"employeeId\":\"$EMP_ID\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-12-31\",\"role\":\"worker\"}"

ci_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/checkin" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $EMP_TOKEN" \
    -d "{\"siteId\":\"$SITE_ID\",\"latitude\":21.0285,\"longitude\":105.8542}")
if [ "$(echo "$ci_resp" | tail -n 1)" -ne 201 ]; then echo "SETUP FAILED: check-in"; exit 1; fi
CHECKIN_ID=$(echo "$ci_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup complete. EMP_ID=$EMP_ID  CHECKIN_ID=$CHECKIN_ID"
echo ""

HR_LIST_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/checkin"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No token → 401 ---"
run_test "Unauthenticated" 401 -s "$HR_LIST_URL"
echo ""

# ── Test 2: Employee token (no checkins:list perm) → 403 ─────────────────────
echo "--- Test 2: Employee without checkins:list → 403 ---"
run_test "Employee forbidden" 403 -s \
    -H "Authorization: Bearer $EMP_TOKEN" "$HR_LIST_URL"
echo ""

# ── Test 3: Platform admin can list all check-ins → 200 ──────────────────────
echo "--- Test 3: Platform admin lists check-ins → 200 ---"
list_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$HR_LIST_URL")
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

# ── Test 4: Pagination fields present ────────────────────────────────────────
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

# ── Test 5: Filter by employeeId ─────────────────────────────────────────────
echo "--- Test 5: Filter by employeeId → only that employee's records ---"
emp_filter_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$HR_LIST_URL?employeeId=$EMP_ID")
emp_filter_body=$(echo "$emp_filter_resp" | head -n -1)
emp_filter_status=$(echo "$emp_filter_resp" | tail -n 1)
if [ "$emp_filter_status" -eq 200 ]; then
    total=$(echo "$emp_filter_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "$total" -ge 1 ]; then
        echo "PASS: employeeId filter returned $total records"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected >= 1 record for employeeId filter, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $emp_filter_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Filter by siteId ──────────────────────────────────────────────────
echo "--- Test 6: Filter by siteId → records for that site ---"
site_filter_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$HR_LIST_URL?siteId=$SITE_ID")
site_filter_body=$(echo "$site_filter_resp" | head -n -1)
site_filter_status=$(echo "$site_filter_resp" | tail -n 1)
if [ "$site_filter_status" -eq 200 ]; then
    total=$(echo "$site_filter_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "$total" -ge 1 ]; then
        echo "PASS: siteId filter returned $total records"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected >= 1 record for siteId filter, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $site_filter_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Filter by status=valid ───────────────────────────────────────────
echo "--- Test 7: Filter by status=valid ---"
status_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$HR_LIST_URL?status=valid")
status_body=$(echo "$status_resp" | head -n -1)
status_http=$(echo "$status_resp" | tail -n 1)
if [ "$status_http" -eq 200 ]; then
    total=$(echo "$status_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    echo "PASS: status=valid filter returned $total records"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $status_http"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Filter by status=nonexistent → 0 results ─────────────────────────
echo "--- Test 8: Filter by status=nonexistent → 0 results ---"
nostat_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$HR_LIST_URL?status=nonexistent_status")
nostat_body=$(echo "$nostat_resp" | head -n -1)
nostat_http=$(echo "$nostat_resp" | tail -n 1)
if [ "$nostat_http" -eq 200 ]; then
    total=$(echo "$nostat_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "$total" -eq 0 ]; then
        echo "PASS: Unknown status returns 0 records"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected 0 records for unknown status, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $nostat_http"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: Future date range → 0 results ────────────────────────────────────
echo "--- Test 9: from=2030-01-01 → 0 results ---"
future_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$HR_LIST_URL?from=2030-01-01T00:00:00Z")
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

# ── Test 10: sortBy=status asc returns 200 ───────────────────────────────────
echo "--- Test 10: sortBy=status&sortDir=asc → 200 ---"
run_test "Sort by status asc" 200 -s \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$HR_LIST_URL?sortBy=status&sortDir=asc"
echo ""

# ── Test 11: size capped at 100 ───────────────────────────────────────────────
echo "--- Test 11: size=999 capped to 100 → 200 ---"
big_resp=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$HR_LIST_URL?size=999")
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
