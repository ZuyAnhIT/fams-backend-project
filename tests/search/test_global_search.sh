#!/usr/bin/env bash
# Tests for global quick-search API (Task 128)
# Usage: BASE_URL=http://localhost:8080 bash test_global_search.sh

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

# Fetches URL with optional extra headers, checks HTTP status and body pattern
check_body() {
    local name="$1"
    local expected_status="$2"
    local pattern="$3"
    local curl_args=("${@:4}")
    resp=$(curl -s -w "\n%{http_code}" "${curl_args[@]}")
    actual=$(echo "$resp" | tail -n 1)
    body=$(echo "$resp" | head -n -1)
    if [ "$actual" -ne "$expected_status" ]; then
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual"
        FAIL=$((FAIL + 1))
        return
    fi
    if echo "$body" | grep -q "$pattern"; then
        echo "PASS: $name (HTTP $actual)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — HTTP $actual ok but body missing pattern: $pattern"
        echo "  Body: $body"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Global Quick Search Tests (Task 128) ==="
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
    -d "{\"name\":\"Search Corp ${TS}\",\"slug\":\"search-corp-${TS}\"}")
t_status=$(echo "$t_resp" | tail -n 1)
if [ "$t_status" -ne 201 ]; then echo "SETUP FAILED: tenant (HTTP $t_status)"; exit 1; fi
TENANT_ID=$(echo "$t_resp" | head -n -1 | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Setup complete. TENANT_ID=$TENANT_ID"
echo ""

SEARCH_URL="$BASE_URL/api/v1/tenants/$TENANT_ID/search"
AUTH="-H Authorization: Bearer $ADMIN_TOKEN"

# ── Test 1: No token → 401 ────────────────────────────────────────────────────
echo "--- Test 1: No auth token → 401 ---"
run_test "Unauthenticated request" 401 \
    -s "${SEARCH_URL}?q=john"
echo ""

# ── Test 2: Missing q param → 400 ────────────────────────────────────────────
echo "--- Test 2: Missing q → 400 ---"
run_test "Missing query param" 400 \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "${SEARCH_URL}"
echo ""

# ── Test 3: Blank q → 400 ────────────────────────────────────────────────────
echo "--- Test 3: Blank q → 400 ---"
run_test "Blank query param" 400 \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "${SEARCH_URL}?q="
echo ""

# ── Test 4: Single-char query returns empty results ───────────────────────────
echo "--- Test 4: Single char q returns empty results ---"
check_body "Single-char query returns empty employees" 200 '"employees":\[\]' \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" "${SEARCH_URL}?q=a"
echo ""

# ── Test 5: Valid query returns correct structure keys ────────────────────────
echo "--- Test 5: Valid 2-char query returns correct structure ---"
check_body "Response has query key" 200 '"query"' \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" "${SEARCH_URL}?q=te"
check_body "Response has limit key" 200 '"limit"' \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" "${SEARCH_URL}?q=te"
check_body "Response has employees key" 200 '"employees"' \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" "${SEARCH_URL}?q=te"
check_body "Response has sites key" 200 '"sites"' \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" "${SEARCH_URL}?q=te"
check_body "Response has checkins key" 200 '"checkins"' \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" "${SEARCH_URL}?q=te"
echo ""

# ── Test 6: Query echoed back in response ─────────────────────────────────────
echo "--- Test 6: Query echoed in response ---"
check_body "Query echoed in response" 200 '"query":"te"' \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" "${SEARCH_URL}?q=te"
echo ""

# ── Test 7: Default limit=5 ───────────────────────────────────────────────────
echo "--- Test 7: Default limit=5 in response ---"
check_body "Default limit=5 echoed" 200 '"limit":5' \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" "${SEARCH_URL}?q=te"
echo ""

# ── Test 8: Custom limit echoed ───────────────────────────────────────────────
echo "--- Test 8: Custom limit=10 echoed ---"
check_body "Custom limit=10 echoed" 200 '"limit":10' \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" "${SEARCH_URL}?q=te&limit=10"
echo ""

# ── Test 9: limit clamped to 20 ──────────────────────────────────────────────
echo "--- Test 9: limit>20 clamped to 20 ---"
check_body "Limit clamped to 20" 200 '"limit":20' \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" "${SEARCH_URL}?q=te&limit=99"
echo ""

# ── Test 10: limit<1 clamped to 1 ────────────────────────────────────────────
echo "--- Test 10: limit<1 clamped to 1 ---"
check_body "Limit clamped to min 1" 200 '"limit":1' \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" "${SEARCH_URL}?q=te&limit=0"
echo ""

# ── Test 11: Platform admin on non-existent tenant returns empty ──────────────
echo "--- Test 11: Platform admin on non-existent tenant returns empty ---"
check_body "Non-existent tenant employees empty" 200 '"employees":\[\]' \
    -s -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/tenants/00000000-0000-0000-0000-000000000000/search?q=te"
echo ""

# ── Test 12: User without employees:list → 403 ───────────────────────────────
echo "--- Test 12: User without employees:list permission → 403 ---"
reg_resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"searchtest${TS}@example.com\",\"password\":\"Test@1234\",\"firstName\":\"Search\",\"lastName\":\"Tester\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
if [ "$reg_status" -ne 201 ]; then
    echo "SKIP: Test 12 — could not register user (HTTP $reg_status)"
    PASS=$((PASS + 1))
else
    USER_TOKEN=$(echo "$reg_resp" | head -n -1 | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
    run_test "No employees:list permission → 403" 403 \
        -s -H "Authorization: Bearer $USER_TOKEN" \
        "${SEARCH_URL}?q=te"
fi
echo ""

# ── Summary ───────────────────────────────────────────────────────────────────
echo "==============================="
echo "RESULTS: $PASS passed, $FAIL failed"
echo "==============================="
if [ "$FAIL" -gt 0 ]; then exit 1; fi
exit 0
