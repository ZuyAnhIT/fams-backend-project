#!/usr/bin/env bash
# Tests for GET /api/v1/users (platform-admin user search)
# Usage: BASE_URL=http://localhost:8080 bash test_list_users.sh

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

echo "=== List Users Tests ==="
echo "Target: $BASE_URL"
echo ""

# Setup: login as platform admin
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
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
echo ""

# Setup: register a known user to search for
TS=$(date +%s)
SEARCH_EMAIL="searchable.user.$TS@fams.com"
reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$SEARCH_EMAIL\",\"password\":\"Regular@1234\",\"displayName\":\"Searchable User $TS\"}")
reg_status=$(echo "$reg_resp" | tail -n 1)
if [ "$reg_status" -ne 201 ] && [ "$reg_status" -ne 409 ]; then
    echo "SETUP WARNING: Could not register search target user (HTTP $reg_status) — search tests may not find results"
fi
echo "Search target user: $SEARCH_EMAIL"
echo ""

USERS_URL="$BASE_URL/api/v1/users"

# Test 1: Happy path — list all users (no filter)
echo "--- Test 1: Happy path (no filter) ---"
list_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$USERS_URL" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
list_body=$(echo "$list_resp" | head -n -1)
list_status=$(echo "$list_resp" | tail -n 1)

if [ "$list_status" -eq 200 ]; then
    has_content=$(echo "$list_body" | grep -o '"content":\[' | head -1)
    has_total=$(echo "$list_body" | grep -o '"totalElements":[0-9]*' | head -1)
    total=$(echo "$has_total" | cut -d: -f2)
    if [ -n "$has_content" ] && [ "${total:-0}" -ge 1 ]; then
        echo "PASS: Happy path (HTTP 200, totalElements=$total)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Happy path — HTTP 200 but pagination fields missing or empty"
        echo "Body: $list_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Happy path — expected HTTP 200, got HTTP $list_status"
    echo "Body: $list_body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Search by exact email
echo ""
echo "--- Test 2: Search by email ---"
email_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$USERS_URL?search=$(echo "$SEARCH_EMAIL" | sed 's/@/%40/g')" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
email_body=$(echo "$email_resp" | head -n -1)
email_status=$(echo "$email_resp" | tail -n 1)

if [ "$email_status" -eq 200 ]; then
    total=$(echo "$email_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "${total:-0}" -ge 1 ]; then
        echo "PASS: Search by email (HTTP 200, found $total result(s))"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Search by email — expected ≥1 result, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Search by email — expected HTTP 200, got HTTP $email_status"
    FAIL=$((FAIL + 1))
fi

# Test 3: Search by display name (partial)
echo ""
echo "--- Test 3: Search by display name ---"
name_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$USERS_URL?search=Searchable+User" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
name_body=$(echo "$name_resp" | head -n -1)
name_status=$(echo "$name_resp" | tail -n 1)

if [ "$name_status" -eq 200 ]; then
    total=$(echo "$name_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "${total:-0}" -ge 1 ]; then
        echo "PASS: Search by display name (HTTP 200, found $total result(s))"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Search by display name — expected ≥1 result, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Search by display name — expected HTTP 200, got HTTP $name_status"
    FAIL=$((FAIL + 1))
fi

# Test 4: Search with no matching results — non-existent string
echo ""
echo "--- Test 4: Search returns 0 results for unknown query ---"
none_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$USERS_URL?search=zzznonexistent999xyzabc" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
none_body=$(echo "$none_resp" | head -n -1)
none_status=$(echo "$none_resp" | tail -n 1)

if [ "$none_status" -eq 200 ]; then
    total=$(echo "$none_body" | grep -o '"totalElements":[0-9]*' | cut -d: -f2)
    if [ "${total:-0}" -eq 0 ]; then
        echo "PASS: No-match search returns 0 results (HTTP 200)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: No-match search — expected 0 results, got $total"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: No-match search — expected HTTP 200, got HTTP $none_status"
    FAIL=$((FAIL + 1))
fi

# Test 5: Pagination (size=1)
echo ""
echo "--- Test 5: Pagination (size=1) ---"
page_resp=$(curl -s -w "\n%{http_code}" \
    -X GET "$USERS_URL?page=0&size=1" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
page_body=$(echo "$page_resp" | head -n -1)
page_status=$(echo "$page_resp" | tail -n 1)

if [ "$page_status" -eq 200 ]; then
    id_count=$(echo "$page_body" | grep -o '"id":"[^"]*"' | wc -l)
    size_field=$(echo "$page_body" | grep -o '"size":[0-9]*' | head -1 | cut -d: -f2)
    if [ "${id_count:-0}" -eq 1 ] && [ "${size_field:-0}" -eq 1 ]; then
        echo "PASS: Pagination size=1 (HTTP 200, got 1 item)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Pagination size=1 — expected 1 item, got $id_count (size=$size_field)"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Pagination — expected HTTP 200, got HTTP $page_status"
    FAIL=$((FAIL + 1))
fi

# Test 6: Unauthenticated → 401
echo ""
echo "--- Test 6: Unauthenticated ---"
run_test "Unauthenticated" 401 \
    -X GET "$USERS_URL"

# Test 7: Regular user (non-platform-admin) → 403
echo ""
echo "--- Test 7: Regular user forbidden ---"
login2_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$SEARCH_EMAIL\",\"password\":\"Regular@1234\"}")
login2_body=$(echo "$login2_resp" | head -n -1)
login2_status=$(echo "$login2_resp" | tail -n 1)

if [ "$login2_status" -eq 200 ]; then
    REGULAR_TOKEN=$(echo "$login2_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ -n "$REGULAR_TOKEN" ]; then
        run_test "Regular user forbidden" 403 \
            -X GET "$USERS_URL" \
            -H "Authorization: Bearer $REGULAR_TOKEN"
    else
        echo "SKIP: Could not extract regular user token"
    fi
else
    echo "SKIP: Could not login as regular user (HTTP $login2_status) — email verification may be required"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
