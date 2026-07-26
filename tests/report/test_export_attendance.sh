#!/usr/bin/env bash
# Tests for monthly attendance Excel export API (Task 124)
# Verifies the endpoint returns a valid Excel binary response.
# Usage: BASE_URL=http://localhost:8080 bash test_export_attendance.sh

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

echo "=== Attendance Excel Export Tests (Task 124) ==="
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
    -d "{\"name\":\"Export Corp ${TS}\",\"slug\":\"export-corp-${TS}\",\"ownerEmail\":\"admin@fams.com\"}")
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then echo "SETUP FAILED: tenant (HTTP $t_status)"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup complete. TENANT_ID=$TENANT_ID"
echo ""

EXPORT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/reports/attendance/export"
YEAR=$(date +%Y)
MONTH=$(date +%-m)

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No auth token → 401 ---"
run_test "Unauthenticated request" 401 \
    -s "$EXPORT_URL?year=$YEAR&month=$MONTH"
echo ""

# ── Test 2: Missing year → 400 ───────────────────────────────────────────────
echo "--- Test 2: Missing year param → 400 ---"
run_test "Missing year parameter" 400 \
    -s "$EXPORT_URL?month=$MONTH" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 3: Missing month → 400 ──────────────────────────────────────────────
echo "--- Test 3: Missing month param → 400 ---"
run_test "Missing month parameter" 400 \
    -s "$EXPORT_URL?year=$YEAR" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 4: Invalid month → 400 ──────────────────────────────────────────────
echo "--- Test 4: month=0 → 400 ---"
run_test "Invalid month zero" 400 \
    -s "$EXPORT_URL?year=$YEAR&month=0" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 5: month=13 → 400 ───────────────────────────────────────────────────
echo "--- Test 5: month=13 → 400 ---"
run_test "Invalid month thirteen" 400 \
    -s "$EXPORT_URL?year=$YEAR&month=13" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 6: Valid request → 200 with Excel content-type ──────────────────────
echo "--- Test 6: Valid export request → 200 with correct Content-Type ---"
export_resp=$(curl -s -o /tmp/attendance_export_test.xlsx -w "%{http_code}\n%{content_type}" \
    "$EXPORT_URL?year=$YEAR&month=$MONTH" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
export_status=$(echo "$export_resp" | head -1)
export_ctype=$(echo "$export_resp" | tail -1)
if [ "$export_status" -eq 200 ]; then
    echo "PASS: Export returned HTTP 200"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected HTTP 200, got $export_status"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 7: Content-Type is Excel MIME type ───────────────────────────────────
echo "--- Test 7: Content-Type is application/vnd.openxmlformats... ---"
if [ "$export_status" -eq 200 ]; then
    if echo "$export_ctype" | grep -q "openxmlformats"; then
        echo "PASS: Content-Type contains openxmlformats ($export_ctype)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected Excel Content-Type, got: $export_ctype"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 6 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 8: Response body is non-empty (valid binary) ─────────────────────────
echo "--- Test 8: Response body is non-empty ---"
if [ "$export_status" -eq 200 ]; then
    file_size=$(wc -c < /tmp/attendance_export_test.xlsx 2>/dev/null || echo "0")
    if [ "${file_size:-0}" -gt 0 ]; then
        echo "PASS: Excel file is non-empty (${file_size} bytes)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Excel file is empty"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: Test 6 failed"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: Content-Disposition header sets attachment filename ───────────────
echo "--- Test 9: Content-Disposition header contains attachment filename ---"
disp_header=$(curl -s -o /dev/null -D - \
    "$EXPORT_URL?year=$YEAR&month=$MONTH" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    | grep -i "content-disposition" || true)
if echo "$disp_header" | grep -qi "attachment"; then
    echo "PASS: Content-Disposition: $disp_header"
    PASS=$((PASS + 1))
else
    echo "FAIL: Content-Disposition header missing or not attachment — '$disp_header'"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 10: Filter by siteId → 200 ──────────────────────────────────────────
echo "--- Test 10: Filter by non-existent siteId → 200 (empty sheet) ---"
run_test "Export with siteId filter" 200 \
    -s "$EXPORT_URL?year=$YEAR&month=$MONTH&siteId=00000000-0000-0000-0000-000000000001" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Test 11: Far-future month → 200 (empty sheet) ────────────────────────────
echo "--- Test 11: Far-future month → 200 (empty sheet) ---"
run_test "Export future month" 200 \
    -s "$EXPORT_URL?year=2099&month=12" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
echo ""

# ── Cleanup ───────────────────────────────────────────────────────────────────
rm -f /tmp/attendance_export_test.xlsx

# ── Summary ───────────────────────────────────────────────────────────────────
echo "================================"
echo "Results: $PASS passed, $FAIL failed"
echo "================================"
[ "$FAIL" -eq 0 ]
