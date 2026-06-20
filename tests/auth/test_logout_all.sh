#!/usr/bin/env bash
# Tests for POST /api/v1/auth/logout/all (logout from all devices)
# Usage: BASE_URL=http://localhost:8080 bash test_logout_all.sh

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

do_login() {
    curl -s -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"admin@fams.com\",\"password\":\"Admin@1234\",\"deviceId\":\"$1\"}"
}

echo "=== Auth Logout-All Tests ==="
echo "Target: $BASE_URL"
echo ""

# Sleep 1s so tokens issued in this run have iat strictly after any revoke timestamp
# set by a prior test suite in the same second.
sleep 1

# Setup: login from two devices
echo "--- Setup: Login from device A ---"
resp_a=$(do_login "device-a")
TOKEN_A=$(echo "$resp_a" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$TOKEN_A" ]; then
    echo "FAIL: Setup — could not get token for device A"
    exit 1
fi
echo "PASS: Device A token obtained"

echo ""
echo "--- Setup: Login from device B ---"
resp_b=$(do_login "device-b")
TOKEN_B=$(echo "$resp_b" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$TOKEN_B" ]; then
    echo "FAIL: Setup — could not get token for device B"
    exit 1
fi
echo "PASS: Device B token obtained"
echo ""

# Test 1: Logout-all without auth → 401
echo "--- Test 1: Logout-all without auth ---"
run_test "Logout-all - no auth" 401 \
    -X POST "$BASE_URL/api/v1/auth/logout/all" \
    -H "Content-Type: application/json"

# Test 2: Logout-all from device A → 200
echo ""
echo "--- Test 2: Logout-all from device A ---"
run_test "Logout-all - happy path" 200 \
    -X POST "$BASE_URL/api/v1/auth/logout/all" \
    -H "Authorization: Bearer $TOKEN_A"

# Test 3: Device A token rejected after logout-all (blacklisted)
echo ""
echo "--- Test 3: Device A token rejected ---"
run_test "Logout-all - device A token blocked" 401 \
    -X POST "$BASE_URL/api/v1/auth/logout/all" \
    -H "Authorization: Bearer $TOKEN_A"

# Test 4: Device B token also rejected (issued before revoke timestamp)
echo ""
echo "--- Test 4: Device B token rejected (user-level revoke) ---"
run_test "Logout-all - device B token blocked" 401 \
    -X POST "$BASE_URL/api/v1/auth/logout/all" \
    -H "Authorization: Bearer $TOKEN_B"

# Test 5: New login after logout-all works — new tokens are valid
# Sleep 1s so the new token's iat (second-precision) is strictly after the revoke timestamp
echo ""
echo "--- Test 5: New login after logout-all succeeds ---"
sleep 1
resp_new=$(do_login "device-new")
TOKEN_NEW=$(echo "$resp_new" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -n "$TOKEN_NEW" ]; then
    echo "PASS: New login after logout-all (token obtained)"
    PASS=$((PASS + 1))
else
    echo "FAIL: New login after logout-all — no token returned"
    echo "Response: $resp_new"
    FAIL=$((FAIL + 1))
fi

# Test 6: New token works on protected endpoint
echo ""
echo "--- Test 6: New token accepted on protected endpoint ---"
run_test "Logout-all - new token accepted" 200 \
    -X POST "$BASE_URL/api/v1/auth/logout/all" \
    -H "Authorization: Bearer $TOKEN_NEW"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
