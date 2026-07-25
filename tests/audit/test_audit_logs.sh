#!/usr/bin/env bash
# Tests for Audit Log API (Tasks 136, 137, 138)
# GET  /api/v1/audit-logs
# GET  /api/v1/audit-logs/{id}
# Usage: BASE_URL=http://localhost:8080 bash test_audit_logs.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

# ── Helpers ───────────────────────────────────────────────────────────────────

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

check_contains() {
    local name="$1" haystack="$2" needle="$3"
    if echo "$haystack" | grep -q "$needle"; then
        echo "PASS: $name (contains '$needle')"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — response did not contain '$needle'"
        echo "  Body: $haystack"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Audit Log Tests (Tasks 136, 137, 138) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: login as platform admin ────────────────────────────────────────────
echo "--- Setup ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then
    echo "SETUP FAILED: platform admin login returned $login_status"
    exit 1
fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Platform admin logged in."

TS=$(date +%s)

# Create a tenant (this will produce an audit log entry if audit is wired in, but for
# this test we directly exercise the GET endpoint which should return the empty or populated list)
t_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Audit Corp ${TS}\",\"slug\":\"audit-corp-${TS}\"}")
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then echo "SETUP FAILED: create tenant returned $t_status"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Tenant created: $TENANT_ID"

# Invite + accept an employee to create a non-admin user
EMP_EMAIL="audit.emp.${TS}@example.com"
curl -s -o /dev/null \
    -X POST "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"email\":\"$EMP_EMAIL\",\"firstName\":\"Audit\",\"lastName\":\"Emp\"}"

inv_page=$(curl -s "$BASE_URL/api/v1/tenants/$TENANT_ID/invitations" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
INV_TOKEN=$(echo "$inv_page" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)

accept_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/invitations/accept" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$INV_TOKEN\",\"password\":\"Employee@1234\"}")
accept_status=$(echo "$accept_resp" | tail -n 1)
if [ "$accept_status" -ne 200 ] && [ "$accept_status" -ne 201 ]; then
    echo "SETUP FAILED: invitation accept returned $accept_status"
    exit 1
fi

emp_login=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMP_EMAIL\",\"password\":\"Employee@1234\"}")
EMP_TOKEN=$(echo "$emp_login" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Employee logged in."
echo ""

# ── 1. Happy path: GET /api/v1/audit-logs as platform admin ───────────────────
echo "--- 1. List audit logs ---"
list_resp=$(curl -s -w "\n%{http_code}" \
    "$BASE_URL/api/v1/audit-logs" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_status=$(echo "$list_resp" | tail -n 1)
list_body=$(echo "$list_resp" | head -n -1)

run_test "GET /api/v1/audit-logs as PLATFORM_ADMIN returns 200" 200 \
    "$BASE_URL/api/v1/audit-logs" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

check_contains "Response has 'content' array" "$list_body" '"content"'
check_contains "Response has 'totalElements'" "$list_body" '"totalElements"'
check_contains "Response has 'success':true" "$list_body" '"success":true'

# ── 2. Filter by tenantId ─────────────────────────────────────────────────────
echo ""
echo "--- 2. Filter by tenantId ---"
run_test "GET /api/v1/audit-logs?tenantId=... returns 200" 200 \
    "$BASE_URL/api/v1/audit-logs?tenantId=$TENANT_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# ── 3. Filter by action ───────────────────────────────────────────────────────
echo ""
echo "--- 3. Filter by action ---"
run_test "GET /api/v1/audit-logs?action=CREATE returns 200" 200 \
    "$BASE_URL/api/v1/audit-logs?action=CREATE" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# ── 4. Filter by date range ───────────────────────────────────────────────────
echo ""
echo "--- 4. Filter by date range ---"
FROM_DATE="2020-01-01T00:00:00Z"
TO_DATE="2099-12-31T23:59:59Z"
run_test "GET /api/v1/audit-logs with date range returns 200" 200 \
    "$BASE_URL/api/v1/audit-logs?from=${FROM_DATE}&to=${TO_DATE}" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# ── 5. Trace by requestId ─────────────────────────────────────────────────────
echo ""
echo "--- 5. Trace by requestId ---"
trace_resp=$(curl -s -w "\n%{http_code}" \
    "$BASE_URL/api/v1/audit-logs?requestId=nonexistent-req-id-${TS}" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
trace_status=$(echo "$trace_resp" | tail -n 1)
trace_body=$(echo "$trace_resp" | head -n -1)

check_val "Trace by requestId returns 200" "$trace_status" "200"
check_contains "Trace response has 'content'" "$trace_body" '"content"'

# ── 6. Unauthenticated ───────────────────────────────────────────────────────
echo ""
echo "--- 6. Unauthenticated ---"
run_test "GET /api/v1/audit-logs without token returns 401" 401 \
    "$BASE_URL/api/v1/audit-logs"

# ── 7. Forbidden — employee role ─────────────────────────────────────────────
echo ""
echo "--- 7. Forbidden (employee) ---"
run_test "GET /api/v1/audit-logs as EMPLOYEE returns 403" 403 \
    "$BASE_URL/api/v1/audit-logs" \
    -H "Authorization: Bearer $EMP_TOKEN"

# ── 8. GET /{id} — not found ─────────────────────────────────────────────────
echo ""
echo "--- 8. GET by ID - not found ---"
run_test "GET /api/v1/audit-logs/{nonexistent} returns 404" 404 \
    "$BASE_URL/api/v1/audit-logs/00000000-0000-0000-0000-000000000001" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# ── 9. GET /{id} — forbidden for employee ────────────────────────────────────
echo ""
echo "--- 9. GET by ID - forbidden for employee ---"
run_test "GET /api/v1/audit-logs/{id} as EMPLOYEE returns 403" 403 \
    "$BASE_URL/api/v1/audit-logs/00000000-0000-0000-0000-000000000001" \
    -H "Authorization: Bearer $EMP_TOKEN"

# ── 10. Pagination params ─────────────────────────────────────────────────────
echo ""
echo "--- 10. Pagination ---"
paged_resp=$(curl -s "$BASE_URL/api/v1/audit-logs?page=0&size=5" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
check_contains "Paginated response has page info" "$paged_resp" '"page"'

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "============================================"
echo "Results: $PASS passed, $FAIL failed"
echo "============================================"
[ "$FAIL" -eq 0 ]
