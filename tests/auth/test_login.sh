#!/usr/bin/env bash
# Tests for POST /api/v1/auth/login
# Usage: BASE_URL=http://localhost:8080 bash test_login.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

TS=$(date +%s)

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

echo "=== Auth Login Tests ==="
echo "Target: $BASE_URL"
echo ""

# Test 1: Happy path — correct credentials should return 200 with tokens
# (admin@fams.com was seeded with email_verified=true via migration)
echo "--- Test 1: Happy path (correct credentials) ---"
response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')

body=$(echo "$response" | head -n -1)
status=$(echo "$response" | tail -n 1)

if [ "$status" -eq 200 ]; then
    access_token=$(echo "$body" | grep -o '"accessToken":"[^"]*"' | head -1)
    refresh_token=$(echo "$body" | grep -o '"refreshToken":"[^"]*"' | head -1)
    if [ -n "$access_token" ] && [ -n "$refresh_token" ]; then
        echo "PASS: Happy path (HTTP 200, tokens present)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Happy path — HTTP 200 but tokens missing in response"
        echo "Body: $body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Happy path — expected HTTP 200, got HTTP $status"
    echo "Body: $body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Wrong password → 401
echo ""
echo "--- Test 2: Wrong password ---"
run_test "Wrong password" 401 \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"WrongPass1"}'

# Test 3: Missing email field → 400
echo ""
echo "--- Test 3: Missing email ---"
run_test "Missing email" 400 \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"password":"Admin@1234"}'

# Test 4: Non-existent email → 401
echo ""
echo "--- Test 4: Non-existent email ---"
run_test "Non-existent email" 401 \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"nobody@fams.com","password":"Admin@1234"}'

# Test 5: Unverified email user cannot login → 403
echo ""
echo "--- Test 5: Unverified email user cannot login ---"
UNVERIFIED_EMAIL="unverified_${TS}@fams.com"
# Register a new user (email not yet verified)
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$UNVERIFIED_EMAIL\",\"password\":\"TestPass1\",\"displayName\":\"Unverified User\"}"

run_test "Unverified email login blocked" 403 \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$UNVERIFIED_EMAIL\",\"password\":\"TestPass1\"}"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
