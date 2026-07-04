#!/usr/bin/env bash
# Tests for violation report API (Task 125)
# Verifies aggregate stats + paginated records for a date range.
# Usage: BASE_URL=http://localhost:8080 bash test_violation_report.sh

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

echo "=== Violation Report Tests (Task 125) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: admin login ────────────────────────────────────────────────────────
echo "--- Setup: platform admin login ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: admin login (HTTP $login_status)"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

# ── Setup: create isolated tenant ─────────────────────────────────────────────
echo "--- Setup: create tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Violation Report Corp ${TS}\",\"slug\":\"viol-rpt-${TS}\"}")
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then echo "SETUP FAILED: tenant (HTTP $t_status)"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup complete. TENANT_ID=$TENANT_ID"
echo ""

REPORT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/reports/violations"
TODAY=$(date +%Y-%m-%d)
YEAR=$(date +%Y)
MONTH=$(date +%m)
FROM="${YEAR}-${MONTH}-01"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No auth token → 401 ---"
run_test "Unauthenticated request" 401 \
    -s "$REPORT_URL"
echo ""

# ── Test 2: No filters → 200 (all violations for tenant) ─────────────────────
echo "--- Test 2: No filters → 200 ---"
report_resp=$(curl -s -w "\n%{http_code}" \
    "$REPORT_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
report_body=$(echo "$report_resp" | head -n -1)
report_status=$(echo "$report_resp" | tail -n 1)
if [ "$report_status" -eq 200 ]; then
    echo "PASS: No-filter report returned HTTP 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $report_status — $report_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Response has all required aggregate fields ────────────────────────
echo "--- Test 3: Response contains required aggregate fields ---"
if [ "$report_status" -eq 200 ]; then
    has_total=$(echo "$report_body" | grep -c '"totalViolations"' || true)
    has_resolved=$(echo "$report_body" | grep -c '"resolvedCount"' || true)
    has_unresolved=$(echo "$report_body" | grep -c '"unresolvedCount"' || true)
    has_affects=$(echo "$report_body" | grep -c '"affectsAttendanceCount"' || true)
    has_by_type=$(echo "$report_body" | grep -c '"byViolationType"' || true)
    has_by_site=$(echo "$report_body" | grep -c '"bySite"' || true)
    has_by_emp=$(echo "$report_body" | grep -c '"byEmployee"' || true)
    has_records=$(echo "$report_body" | grep -c '"records"' || true)
    if [ "${has_total:-0}" -ge 1 ] && [ "${has_resolved:-0}" -ge 1 ] && \
       [ "${has_unresolved:-0}" -ge 1 ] && [ "${has_affects:-0}" -ge 1 ] && \
       [ "${has_by_type:-0}" -ge 1 ] && [ "${has_by_site:-0}" -ge 1 ] && \
       [ "${has_by_emp:-0}" -ge 1 ] && [ "${has_records:-0}" -ge 1 ]; then
        echo "PASS: Response has all required aggregate fields"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing required fields — $report_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 2 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: records section has pagination metadata ───────────────────────────
echo "--- Test 4: records section has pagination metadata ---"
if [ "$report_status" -eq 200 ]; then
    has_content=$(echo "$report_body" | grep -c '"content"' || true)
    has_total_el=$(echo "$report_body" | grep -c '"totalElements"' || true)
    if [ "${has_content:-0}" -ge 1 ] && [ "${has_total_el:-0}" -ge 1 ]; then
        echo "PASS: records section has content, totalElements"
        PASS=$((PASS + 1))
    else
        echo "FAIL: records section missing pagination fields — $report_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 2 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Empty tenant has zero counts ──────────────────────────────────────
echo "--- Test 5: Fresh tenant has totalViolations=0 ---"
if [ "$report_status" -eq 200 ]; then
    total=$(echo "$report_body" | grep -o '"totalViolations":[0-9]*' | cut -d: -f2)
    if [ "${total:-0}" -eq 0 ]; then
        echo "PASS: Fresh tenant returns totalViolations=0"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected 0, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 2 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Date range filter → 200 ──────────────────────────────────────────
echo "--- Test 6: Date range filter (from/to) → 200 ---"
run_test "Date range filter" 200 \
    -s "$REPORT_URL?from=$FROM&to=$TODAY" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 7: Site filter → 200 ────────────────────────────────────────────────
echo "--- Test 7: siteId filter → 200 ---"
run_test "Site filter" 200 \
    -s "$REPORT_URL?siteId=00000000-0000-0000-0000-000000000001" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 8: Employee filter → 200 ────────────────────────────────────────────
echo "--- Test 8: employeeId filter → 200 ---"
run_test "Employee filter" 200 \
    -s "$REPORT_URL?employeeId=00000000-0000-0000-0000-000000000002" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 9: Violation type filter → 200 ──────────────────────────────────────
echo "--- Test 9: violationType filter → 200 ---"
run_test "Violation type filter" 200 \
    -s "$REPORT_URL?violationType=no_response" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 10: All filters combined → 200 ──────────────────────────────────────
echo "--- Test 10: All filters combined → 200 ---"
run_test "All filters combined" 200 \
    -s "$REPORT_URL?from=$FROM&to=$TODAY&violationType=location_fail&page=0&size=5" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 11: Custom pagination → 200 ─────────────────────────────────────────
echo "--- Test 11: Custom pagination params → 200 ---"
run_test "Custom pagination" 200 \
    -s "$REPORT_URL?page=0&size=5" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 12: resolvedCount + unresolvedCount = totalViolations ────────────────
echo "--- Test 12: resolvedCount + unresolvedCount = totalViolations ---"
if [ "$report_status" -eq 200 ]; then
    total_v=$(echo "$report_body" | grep -o '"totalViolations":[0-9]*' | cut -d: -f2)
    resolved=$(echo "$report_body" | grep -o '"resolvedCount":[0-9]*' | cut -d: -f2)
    unresolved=$(echo "$report_body" | grep -o '"unresolvedCount":[0-9]*' | cut -d: -f2)
    expected_total=$((${resolved:-0} + ${unresolved:-0}))
    if [ "${total_v:-0}" -eq "$expected_total" ]; then
        echo "PASS: resolvedCount ($resolved) + unresolvedCount ($unresolved) = totalViolations ($total_v)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $resolved + $unresolved != $total_v"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 2 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
