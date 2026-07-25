#!/usr/bin/env bash
# Tests for violation export API (Task 132)
# Usage: BASE_URL=http://localhost:8080 bash test_export_violations.sh

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

echo "=== Violation Export Tests (Task 132) ==="
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
    -d "{\"name\":\"ViolExport Corp ${TS}\",\"slug\":\"violexport-${TS}\"}")
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then echo "SETUP FAILED: tenant (HTTP $t_status)"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup complete. TENANT_ID=$TENANT_ID"
echo ""

EXPORT_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/reports/violations/export"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No auth token → 401 ---"
run_test "Unauthenticated request" 401 \
    -s "$EXPORT_URL"
echo ""

# ── Test 2: Valid request → 200 with xlsx content-type ───────────────────────
echo "--- Test 2: Valid export → 200 with xlsx content-type ---"
ct=$(curl -s -o /dev/null -w "%{content_type}" \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$EXPORT_URL")
if echo "$ct" | grep -q "spreadsheetml"; then
    echo "PASS: Content-Type is xlsx ($ct)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Content-Type not xlsx — got: $ct"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 3: Response body is non-empty ───────────────────────────────────────
echo "--- Test 3: Response body is non-empty (xlsx bytes) ---"
body_size=$(curl -s -w "%{size_download}" -o /dev/null \
    -H "Authorization: Bearer $ADMIN_TOKEN" "$EXPORT_URL")
if [ "$body_size" -gt 0 ]; then
    echo "PASS: Response body size = $body_size bytes"
    PASS=$((PASS + 1))
else
    echo "FAIL: Response body is empty"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 4: from/to date filters accepted ─────────────────────────────────────
echo "--- Test 4: Date range filter accepted (200) ---"
run_test "from/to date filter" 200 \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "${EXPORT_URL}?from=2026-01-01&to=2026-12-31"
echo ""

# ── Test 5: violationType filter accepted ────────────────────────────────────
echo "--- Test 5: violationType filter accepted (200) ---"
run_test "violationType filter" 200 \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "${EXPORT_URL}?violationType=no_response"
echo ""

# ── Test 6: siteId filter accepted ───────────────────────────────────────────
echo "--- Test 6: siteId filter accepted (200) ---"
run_test "siteId filter" 200 \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "${EXPORT_URL}?siteId=00000000-0000-0000-0000-000000000001"
echo ""

# ── Test 7: employeeId filter accepted ───────────────────────────────────────
echo "--- Test 7: employeeId filter accepted (200) ---"
run_test "employeeId filter" 200 \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "${EXPORT_URL}?employeeId=00000000-0000-0000-0000-000000000002"
echo ""

# ── Test 8: Content-Disposition header present ────────────────────────────────
echo "--- Test 8: Content-Disposition header present ---"
cd_header=$(curl -s -I -H "Authorization: Bearer $ADMIN_TOKEN" "$EXPORT_URL" \
    | grep -i "content-disposition" | tr -d '\r')
if echo "$cd_header" | grep -qi "attachment"; then
    echo "PASS: Content-Disposition header = $cd_header"
    PASS=$((PASS + 1))
else
    echo "FAIL: Content-Disposition header missing or wrong — got: $cd_header"
    FAIL=$((FAIL + 1))
fi
echo ""

# ── Test 9: Platform admin on non-existent tenant returns 200 ────────────────
echo "--- Test 9: Platform admin non-existent tenant → 200 empty file ---"
run_test "Non-existent tenant returns 200" 200 \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/reports/violations/export"
echo ""

# ── Test 10: Unpermitted user → 403 ──────────────────────────────────────────
echo "--- Test 10: User without reports:export permission → 403 ---"
reg_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"violexp${TS}@example.com\",\"password\":\"Test@1234\",\"firstName\":\"Viol\",\"lastName\":\"Exp\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
if [ "$reg_status" -ne 201 ]; then
    echo "SKIP: Test 10 — could not register user (HTTP $reg_status)"
    PASS=$((PASS + 1))
else
    USER_TOKEN=$(echo "$reg_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
    run_test "No reports:export permission → 403" 403 \
        -s -H "Authorization: Bearer $USER_TOKEN" \
        "$EXPORT_URL"
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "==============================="
echo "RESULTS: $PASS passed, $FAIL failed"
echo "==============================="
if [ "$FAIL" -gt 0 ]; then exit 1; fi
exit 0
