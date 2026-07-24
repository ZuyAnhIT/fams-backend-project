#!/usr/bin/env bash
# Tests for POST /api/v1/auth/logout (logout from current device)
# Usage: BASE_URL=http://localhost:8080 bash test_logout.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    local actual_status
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Auth Logout Tests ==="
echo "Target: $BASE_URL"
echo ""

# Sleep 1s so the login token has an iat strictly after any logout-all revoke
# timestamp that a prior test suite may have set in the same second.
sleep 1

# Obtain tokens via email/password login
echo "--- Setup: Login to get tokens ---"
login_response=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234","deviceId":"logout-test-device"}')

ACCESS_TOKEN=$(echo "$login_response" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
REFRESH_TOKEN=$(echo "$login_response" | grep -o '"refreshToken":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$ACCESS_TOKEN" ] || [ -z "$REFRESH_TOKEN" ]; then
    echo "FAIL: Setup — could not obtain tokens from login"
    echo "Response: $login_response"
    exit 1
fi
echo "PASS: Setup — tokens obtained"
echo ""

# Test 1: Logout without Authorization header → 401
echo "--- Test 1: Logout without auth token ---"
run_test "Logout - no auth" 401 \
    -X POST "$BASE_URL/api/v1/auth/logout" \
    -H "Content-Type: application/json" \
    -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}"

# Test 2: Logout without refreshToken body → 400
echo ""
echo "--- Test 2: Logout missing refreshToken in body ---"
run_test "Logout - missing refreshToken" 400 \
    -X POST "$BASE_URL/api/v1/auth/logout" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -d '{}'

# Test 3: Happy path — logout succeeds → 200
echo ""
echo "--- Test 3: Happy path logout ---"
run_test "Logout - happy path" 200 \
    -X POST "$BASE_URL/api/v1/auth/logout" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}"

# Test 4: Access token is blacklisted after logout → 401 on protected endpoint
echo ""
echo "--- Test 4: Blacklisted token rejected ---"
run_test "Logout - blacklisted token rejected" 401 \
    -X POST "$BASE_URL/api/v1/auth/logout" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
