#!/usr/bin/env bash
# Tests for POST /api/v1/auth/login/google
# Usage: BASE_URL=http://localhost:8080 bash test_google_login.sh
#
# NOTE: The happy-path (real Google token) requires a browser and is covered
# by the manual test at http://localhost:8080/google-login-test.html

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

echo "=== Google Login Tests ==="
echo "Target: $BASE_URL"
echo ""

# Test 1: Missing idToken field → 400
echo "--- Test 1: Missing idToken field ---"
run_test "Missing idToken" 400 \
    -X POST "$BASE_URL/api/v1/auth/login/google" \
    -H "Content-Type: application/json" \
    -d '{}'

# Test 2: Blank idToken → 400
echo ""
echo "--- Test 2: Blank idToken ---"
run_test "Blank idToken" 400 \
    -X POST "$BASE_URL/api/v1/auth/login/google" \
    -H "Content-Type: application/json" \
    -d '{"idToken":""}'

# Test 3: Malformed token string → 401
echo ""
echo "--- Test 3: Malformed token ---"
run_test "Malformed token" 401 \
    -X POST "$BASE_URL/api/v1/auth/login/google" \
    -H "Content-Type: application/json" \
    -d '{"idToken":"not.a.real.google.token"}'

# Test 4: Well-formed JWT but wrong issuer/audience → 401
echo ""
echo "--- Test 4: Fake JWT (wrong audience) ---"
FAKE_JWT="eyJhbGciOiJSUzI1NiIsImtpZCI6ImZha2UifQ.eyJzdWIiOiIxMjM0NTY3ODkwIiwiZW1haWwiOiJ0ZXN0QGV4YW1wbGUuY29tIiwiYXVkIjoid3JvbmctY2xpZW50LWlkIiwiaXNzIjoiYWNjb3VudHMuZ29vZ2xlLmNvbSIsImV4cCI6OTk5OTk5OTk5OX0.fakesignature"
run_test "Fake JWT (wrong audience)" 401 \
    -X POST "$BASE_URL/api/v1/auth/login/google" \
    -H "Content-Type: application/json" \
    -d "{\"idToken\":\"$FAKE_JWT\"}"

# Test 5: No Content-Type header → 415
echo ""
echo "--- Test 5: Wrong Content-Type ---"
run_test "Wrong Content-Type" 415 \
    -X POST "$BASE_URL/api/v1/auth/login/google" \
    -d '{"idToken":"sometoken"}'

# Test 6: Endpoint reachable without auth token (not blocked by Spring Security)
# Invalid token returns 401 from the service (not 403 from the security layer)
echo ""
echo "--- Test 6: Endpoint is publicly accessible (no Authorization header) ---"
RESPONSE=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login/google" \
    -H "Content-Type: application/json" \
    -d '{"idToken":"check-public"}')
STATUS=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n -1)
if [ "$STATUS" -ne 403 ]; then
    echo "PASS: Endpoint is public — not blocked by security filter (HTTP $STATUS, service handled the request)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Endpoint returned 403 — security filter may be blocking unauthenticated requests"
    echo "Body: $BODY"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
echo "NOTE: Happy-path test requires a real Google ID token."
echo "      Visit $BASE_URL/google-login-test.html for the manual test."
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
