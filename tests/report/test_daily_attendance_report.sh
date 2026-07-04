#!/usr/bin/env bash
# Tests for daily attendance report API (Task 122)
# Verifies aggregate stats + paginated records returned for a given date.
# Usage: BASE_URL=http://localhost:8080 bash test_daily_attendance_report.sh

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

echo "=== Daily Attendance Report Tests (Task 122) ==="
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

# ── Setup: create isolated tenant for this test run ───────────────────────────
echo "--- Setup: create tenant ---"
TS=$(date +%s)
t_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Report Corp ${TS}\",\"slug\":\"report-corp-${TS}\"}")
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then echo "SETUP FAILED: tenant (HTTP $t_status)"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup complete. TENANT_ID=$TENANT_ID"
echo ""

REPORT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/reports/attendance/daily"
TODAY=$(date +%Y-%m-%d)

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No auth token → 401 ---"
run_test "Unauthenticated request" 401 \
    -s "$REPORT_URL?date=$TODAY"
echo ""

# ── Test 2: Missing required date param → 400 ─────────────────────────────────
echo "--- Test 2: Missing date param → 400 ---"
run_test "Missing date parameter" 400 \
    -s "$REPORT_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 3: Invalid date format → 400 ────────────────────────────────────────
echo "--- Test 3: Invalid date format → 400 ---"
run_test "Invalid date format" 400 \
    -s "$REPORT_URL?date=not-a-date" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 4: Admin with valid date → 200 ───────────────────────────────────────
echo "--- Test 4: Admin requests daily report → 200 ---"
report_resp=$(curl -s -w "\n%{http_code}" \
    "$REPORT_URL?date=$TODAY" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
report_body=$(echo "$report_resp" | head -n -1)
report_status=$(echo "$report_resp" | tail -n 1)
if [ "$report_status" -eq 200 ]; then
    echo "PASS: Admin daily report returned HTTP 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $report_status — $report_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 5: Response has all required aggregate fields ────────────────────────
echo "--- Test 5: Response contains required aggregate fields ---"
if [ "$report_status" -eq 200 ]; then
    has_date=$(echo "$report_body" | grep -c '"date"' || true)
    has_present=$(echo "$report_body" | grep -c '"totalPresent"' || true)
    has_absent=$(echo "$report_body" | grep -c '"totalAbsent"' || true)
    has_late=$(echo "$report_body" | grep -c '"totalLate"' || true)
    has_early=$(echo "$report_body" | grep -c '"totalEarlyLeave"' || true)
    has_missing=$(echo "$report_body" | grep -c '"totalMissingCheckout"' || true)
    has_work=$(echo "$report_body" | grep -c '"totalWorkMinutes"' || true)
    has_ot=$(echo "$report_body" | grep -c '"totalOtMinutes"' || true)
    has_absent_ids=$(echo "$report_body" | grep -c '"absentEmployeeIds"' || true)
    has_records=$(echo "$report_body" | grep -c '"records"' || true)
    if [ "${has_date:-0}" -ge 1 ] && [ "${has_present:-0}" -ge 1 ] && \
       [ "${has_absent:-0}" -ge 1 ] && [ "${has_late:-0}" -ge 1 ] && \
       [ "${has_early:-0}" -ge 1 ] && [ "${has_missing:-0}" -ge 1 ] && \
       [ "${has_work:-0}" -ge 1 ] && [ "${has_ot:-0}" -ge 1 ] && \
       [ "${has_absent_ids:-0}" -ge 1 ] && [ "${has_records:-0}" -ge 1 ]; then
        echo "PASS: Response has all required fields"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Missing required fields in response — $report_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 4 failed, skipping field validation"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 6: Records section has pagination metadata ───────────────────────────
echo "--- Test 6: records section has pagination metadata ---"
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
    echo "SKIP: Test 4 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Filter by siteId → 200 ───────────────────────────────────────────
echo "--- Test 7: Filter by non-existent siteId → 200 with empty results ---"
FAKE_SITE_ID="00000000-0000-0000-0000-000000000001"
site_resp=$(curl -s -w "\n%{http_code}" \
    "$REPORT_URL?date=$TODAY&siteId=$FAKE_SITE_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
site_body=$(echo "$site_resp" | head -n -1)
site_status=$(echo "$site_resp" | tail -n 1)
if [ "$site_status" -eq 200 ]; then
    present=$(echo "$site_body" | grep -o '"totalPresent":[0-9]*' | cut -d: -f2)
    if [ "${present:-0}" -eq 0 ]; then
        echo "PASS: Filter by non-existent siteId returned HTTP 200 with totalPresent=0"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected totalPresent=0 for non-existent site, got $present"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $site_status — $site_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Pagination params respected → 200 ────────────────────────────────
echo "--- Test 8: Custom page/size params → 200 ---"
run_test "Custom pagination params" 200 \
    -s "$REPORT_URL?date=$TODAY&page=0&size=5" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 9: Future date (no data) → 200 with zeros ───────────────────────────
echo "--- Test 9: Future date returns 200 with zero counts ---"
future_resp=$(curl -s -w "\n%{http_code}" \
    "$REPORT_URL?date=2099-12-31" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
future_body=$(echo "$future_resp" | head -n -1)
future_status=$(echo "$future_resp" | tail -n 1)
if [ "$future_status" -eq 200 ]; then
    future_present=$(echo "$future_body" | grep -o '"totalPresent":[0-9]*' | cut -d: -f2)
    if [ "${future_present:-0}" -eq 0 ]; then
        echo "PASS: Future date returned HTTP 200 with totalPresent=0"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected totalPresent=0 for future date, got $future_present"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $future_status — $future_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 10: Platform admin on non-existent tenant returns empty report ────────
echo "--- Test 10: Platform admin on non-existent tenant → 200 empty report ---"
fake_resp=$(curl -s -w "\n%{http_code}" \
    "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/reports/attendance/daily?date=$TODAY" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
fake_body=$(echo "$fake_resp" | head -n -1)
fake_status=$(echo "$fake_resp" | tail -n 1)
if [ "$fake_status" -eq 200 ]; then
    fake_present=$(echo "$fake_body" | grep -o '"totalPresent":[0-9]*' | cut -d: -f2)
    if [ "${fake_present:-0}" -eq 0 ]; then
        echo "PASS: Non-existent tenant returns HTTP 200 with totalPresent=0 (platform admin bypasses tenant check)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected totalPresent=0 for non-existent tenant, got $fake_present"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got $fake_status — $fake_body"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
