#!/usr/bin/env bash
# Tests for GET /api/v1/tenants (list tenants with search/filter/sort/pagination)
# Usage: BASE_URL=http://localhost:8080 bash test_list_tenants.sh

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

echo "=== Tenant List Tests ==="
echo "Target: $BASE_URL"
echo ""

# Obtain platform admin token
echo "--- Setup: Login as platform admin ---"
login_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')

login_body=$(echo "$login_response" | head -n -1)
login_status=$(echo "$login_response" | tail -n 1)

if [ "$login_status" -ne 200 ]; then
    echo "SETUP FAILED: Could not login as admin (HTTP $login_status)"
    exit 1
fi

ADMIN_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$ADMIN_TOKEN" ]; then
    echo "SETUP FAILED: Could not extract admin token"
    exit 1
fi
echo "Admin token obtained."

# Seed a couple of tenants for list tests
TS=$(date +%s)
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Alpha Corp\",\"slug\":\"alpha-corp-$TS\",\"industry\":\"Finance\",\"countryCode\":\"US\"}"

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/tenants" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d "{\"name\":\"Beta Corp\",\"slug\":\"beta-corp-$TS\",\"industry\":\"Tech\",\"countryCode\":\"VN\"}"

echo "Seed tenants created."
echo ""

# Test 1: Happy path — list with no filters
echo "--- Test 1: Happy path (no filters) ---"
list_response=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants" \
    -H "Authorization: Bearer $ADMIN_TOKEN")

list_body=$(echo "$list_response" | head -n -1)
list_status=$(echo "$list_response" | tail -n 1)

if [ "$list_status" -eq 200 ]; then
    has_content=$(echo "$list_body" | grep -o '"content":\[' | head -1)
    has_total=$(echo "$list_body" | grep -o '"totalElements":[0-9]*' | head -1)
    if [ -n "$has_content" ] && [ -n "$has_total" ]; then
        echo "PASS: Happy path (HTTP 200, $has_total)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Happy path — HTTP 200 but pagination fields missing"
        echo "Body: $list_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Happy path — expected HTTP 200, got HTTP $list_status"
    echo "Body: $list_body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Search by name keyword
echo ""
echo "--- Test 2: Search by name ---"
search_response=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants?search=alpha-corp-$TS" \
    -H "Authorization: Bearer $ADMIN_TOKEN")

search_body=$(echo "$search_response" | head -n -1)
search_status=$(echo "$search_response" | tail -n 1)

if [ "$search_status" -eq 200 ]; then
    total=$(echo "$search_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "${total:-0}" -ge 1 ]; then
        echo "PASS: Search by name (HTTP 200, found $total result(s))"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Search by name — expected at least 1 result, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Search by name — expected HTTP 200, got HTTP $search_status"
    FAIL=$((FAIL + 1))
fi

# Test 3: Filter by status=trial
echo ""
echo "--- Test 3: Filter by status=trial ---"
run_test "Filter by status" 200 \
    -X GET "$BASE_URL/api/v1/tenants?status=trial" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 4: Filter by industry
echo ""
echo "--- Test 4: Filter by industry ---"
industry_response=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants?industry=Finance" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
industry_body=$(echo "$industry_response" | head -n -1)
industry_status=$(echo "$industry_response" | tail -n 1)

if [ "$industry_status" -eq 200 ]; then
    total=$(echo "$industry_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "${total:-0}" -ge 1 ]; then
        echo "PASS: Filter by industry (HTTP 200, found $total result(s))"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Filter by industry — expected at least 1 result, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Filter by industry — expected HTTP 200, got HTTP $industry_status"
    FAIL=$((FAIL + 1))
fi

# Test 5: Pagination params
echo ""
echo "--- Test 5: Pagination (page=0, size=1) ---"
page_response=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/tenants?page=0&size=1" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
page_body=$(echo "$page_response" | head -n -1)
page_status=$(echo "$page_response" | tail -n 1)

if [ "$page_status" -eq 200 ]; then
    content_count=$(echo "$page_body" | grep -o '"id":"[^"]*"' | wc -l)
    if [ "$content_count" -le 1 ]; then
        echo "PASS: Pagination size=1 (HTTP 200, content has $content_count item)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Pagination size=1 — expected at most 1 item in content, got $content_count"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Pagination — expected HTTP 200, got HTTP $page_status"
    FAIL=$((FAIL + 1))
fi

# Test 6: Sort by name ascending
echo ""
echo "--- Test 6: Sort by name asc ---"
run_test "Sort by name asc" 200 \
    -X GET "$BASE_URL/api/v1/tenants?sortBy=name&sortDir=asc" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 7: Invalid sortDir still works (defaults to desc)
echo ""
echo "--- Test 7: Invalid sortDir defaults gracefully ---"
run_test "Invalid sortDir" 200 \
    -X GET "$BASE_URL/api/v1/tenants?sortBy=name&sortDir=invalid" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

# Test 8: Unauthenticated → 401
echo ""
echo "--- Test 8: Unauthenticated ---"
run_test "Unauthenticated" 401 \
    -X GET "$BASE_URL/api/v1/tenants"

# Test 9: Regular user → 403
echo ""
echo "--- Test 9: Regular user forbidden ---"
# Register or login regular user
reg_body=$(curl -s \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d '{"email":"regular_list_test@fams.com","password":"Regular@1234","displayName":"Regular"}' 2>/dev/null || true)
if echo "$reg_body" | grep -q '"accessToken"'; then
    REGULAR_TOKEN=$(echo "$reg_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
else
    login2=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d '{"email":"regular_list_test@fams.com","password":"Regular@1234"}')
    REGULAR_TOKEN=$(echo "$login2" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
fi

if [ -n "$REGULAR_TOKEN" ]; then
    run_test "Regular user forbidden" 403 \
        -X GET "$BASE_URL/api/v1/tenants" \
        -H "Authorization: Bearer $REGULAR_TOKEN"
else
    echo "SKIP: Regular user forbidden (could not get regular token)"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
