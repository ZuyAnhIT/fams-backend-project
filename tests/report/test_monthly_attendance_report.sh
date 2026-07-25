#!/usr/bin/env bash
# Tests for monthly attendance report API (Task 123)
# Verifies tenant-wide aggregate stats + paginated per-employee records for a month.
# Usage: BASE_URL=http://localhost:8080 bash test_monthly_attendance_report.sh

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

echo "=== Monthly Attendance Report Tests (Task 123) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: admin login ────────────────────────────────────────────────────────
echo "--- Setup: platform admin login ---"
login_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then echo "SETUP FAILED: admin login (HTTP $login_status)"; exit 1; fi
ADMIN_TOKEN=$(echo "$login_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

# ── Setup: create isolated tenant ─────────────────────────────────────────────
echo "--- Setup: create tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Monthly Report Corp ${TS}\",\"slug\":\"monthly-rpt-${TS}\"}")
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then echo "SETUP FAILED: tenant (HTTP $t_status)"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup complete. TENANT_ID=$TENANT_ID"
echo ""

REPORT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/reports/attendance/monthly"
YEAR=$(date +%Y)
MONTH=$(date +%-m)

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No auth token → 401 ---"
run_test "Unauthenticated request" 401 \
    -s "$REPORT_URL?year=$YEAR&month=$MONTH"
echo ""

# ── Test 2: Missing year param → 400 ─────────────────────────────────────────
echo "--- Test 2: Missing year param → 400 ---"
run_test "Missing year parameter" 400 \
    -s "$REPORT_URL?month=$MONTH" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 3: Missing month param → 400 ────────────────────────────────────────
echo "--- Test 3: Missing month param → 400 ---"
run_test "Missing month parameter" 400 \
    -s "$REPORT_URL?year=$YEAR" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 4: Invalid month (0) → 400 ──────────────────────────────────────────
echo "--- Test 4: month=0 → 400 ---"
run_test "Invalid month zero" 400 \
    -s "$REPORT_URL?year=$YEAR&month=0" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 5: Invalid month (13) → 400 ─────────────────────────────────────────
echo "--- Test 5: month=13 → 400 ---"
run_test "Invalid month thirteen" 400 \
    -s "$REPORT_URL?year=$YEAR&month=13" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 6: Admin with valid year/month → 200 ─────────────────────────────────
echo "--- Test 6: Admin requests monthly report → 200 ---"
report_resp=$(curl -s -w "\n%{http_code}" \
    "$REPORT_URL?year=$YEAR&month=$MONTH" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
report_body=$(echo "$report_resp" | head -n -1)
report_status=$(echo "$report_resp" | tail -n 1)
if [ "$report_status" -eq 200 ]; then
    echo "PASS: Admin monthly report returned HTTP 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $report_status — $report_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Response has all required aggregate fields ────────────────────────
echo "--- Test 7: Response contains required aggregate fields ---"
if [ "$report_status" -eq 200 ]; then
    has_year=$(echo "$report_body" | grep -c '"year"' || true)
    has_month=$(echo "$report_body" | grep -c '"month"' || true)
    has_employees=$(echo "$report_body" | grep -c '"totalEmployees"' || true)
    has_present=$(echo "$report_body" | grep -c '"totalPresentDays"' || true)
    has_work=$(echo "$report_body" | grep -c '"totalWorkMinutes"' || true)
    has_late=$(echo "$report_body" | grep -c '"totalLateDays"' || true)
    has_early=$(echo "$report_body" | grep -c '"totalEarlyLeaveDays"' || true)
    has_missing=$(echo "$report_body" | grep -c '"totalMissingCheckoutDays"' || true)
    has_ot=$(echo "$report_body" | grep -c '"totalOtMinutes"' || true)
    has_records=$(echo "$report_body" | grep -c '"records"' || true)
    if [ "${has_year:-0}" -ge 1 ] && [ "${has_month:-0}" -ge 1 ] && \
       [ "${has_employees:-0}" -ge 1 ] && [ "${has_present:-0}" -ge 1 ] && \
       [ "${has_work:-0}" -ge 1 ] && [ "${has_late:-0}" -ge 1 ] && \
       [ "${has_early:-0}" -ge 1 ] && [ "${has_missing:-0}" -ge 1 ] && \
       [ "${has_ot:-0}" -ge 1 ] && [ "${has_records:-0}" -ge 1 ]; then
        echo "PASS: Response has all required aggregate fields"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing required fields in response — $report_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 6 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: records section has pagination metadata ───────────────────────────
echo "--- Test 8: records section has pagination metadata ---"
if [ "$report_status" -eq 200 ]; then
    has_content=$(echo "$report_body" | grep -c '"content"' || true)
    has_total_el=$(echo "$report_body" | grep -c '"totalElements"' || true)
    has_total_pg=$(echo "$report_body" | grep -c '"totalPages"' || true)
    if [ "${has_content:-0}" -ge 1 ] && [ "${has_total_el:-0}" -ge 1 ] && [ "${has_total_pg:-0}" -ge 1 ]; then
        echo "PASS: records section has content, totalElements, totalPages"
        PASS=$((PASS + 1))
    else
        echo "FAIL: records section missing pagination metadata — $report_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 6 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: Empty tenant returns zero counts ───────────────────────────────────
echo "--- Test 9: Empty tenant returns totalEmployees=0 ---"
if [ "$report_status" -eq 200 ]; then
    emp_count=$(echo "$report_body" | grep -o '"totalEmployees":[0-9]*' | cut -d: -f2)
    if [ "${emp_count:-0}" -eq 0 ]; then
        echo "PASS: Fresh tenant returns totalEmployees=0"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected totalEmployees=0, got $emp_count"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 6 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 10: Filter by siteId → 200 ──────────────────────────────────────────
echo "--- Test 10: Filter by non-existent siteId → 200 with empty results ---"
FAKE_SITE_ID="00000000-0000-0000-0000-000000000001"
run_test "Filter by non-existent siteId" 200 \
    -s "$REPORT_URL?year=$YEAR&month=$MONTH&siteId=$FAKE_SITE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 11: Custom page/size → 200 ──────────────────────────────────────────
echo "--- Test 11: Custom page/size params → 200 ---"
run_test "Custom pagination params" 200 \
    -s "$REPORT_URL?year=$YEAR&month=$MONTH&page=0&size=5" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 12: Far-future month returns zeros ───────────────────────────────────
echo "--- Test 12: Far-future year/month → 200 with zero counts ---"
future_resp=$(curl -s -w "\n%{http_code}" \
    "$REPORT_URL?year=2099&month=12" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
future_body=$(echo "$future_resp" | head -n -1)
future_status=$(echo "$future_resp" | tail -n 1)
if [ "$future_status" -eq 200 ]; then
    fut_emp=$(echo "$future_body" | grep -o '"totalEmployees":[0-9]*' | cut -d: -f2)
    if [ "${fut_emp:-0}" -eq 0 ]; then
        echo "PASS: Future month returns HTTP 200 with totalEmployees=0"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected totalEmployees=0 for future month, got $fut_emp"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $future_status — $future_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
