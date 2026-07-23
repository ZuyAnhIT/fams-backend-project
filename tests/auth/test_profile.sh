#!/usr/bin/env bash
# Tests for GET /api/v1/auth/me
# Usage: BASE_URL=http://localhost:8080 bash test_profile.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

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

echo "=== Auth Profile Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: register a test user (email) and obtain a token ───────────────────
TS=$(date +%s)
TEST_EMAIL="profile_${TS}_$$@fams.com"
PASSWORD="Profile@123"
DISPLAY_NAME="Test Profile User"

echo "--- Setup: registering test user (email=$TEST_EMAIL) ---"
ACCESS_TOKEN=$(register_verified_test_user_token "$BASE_URL" "$DISPLAY_NAME" "$TEST_EMAIL" "$PASSWORD")
if [ -z "$ACCESS_TOKEN" ]; then
    echo "FAIL: Setup — could not extract access token"
    exit 1
fi
echo "Setup OK — token acquired"
echo ""

# Test 1: Unauthenticated request → 401
echo "--- Test 1: No auth token (unauthenticated) ---"
run_test "Unauthenticated" 401 \
    -X GET "$BASE_URL/api/v1/auth/me"

# Test 2: Happy path — returns profile with correct fields
echo ""
echo "--- Test 2: Happy path (valid token) ---"
profile_response=$(curl -s -w "\n%{http_code}" \
    -X GET "$BASE_URL/api/v1/auth/me" \
    -H "Authorization: Bearer $ACCESS_TOKEN")

profile_body=$(echo "$profile_response" | head -n -1)
profile_status=$(echo "$profile_response" | tail -n 1)

if [ "$profile_status" -ne 200 ]; then
    echo "FAIL: Happy path — expected HTTP 200, got HTTP $profile_status"
    echo "Body: $profile_body"
    FAIL=$((FAIL + 1))
else
    # Verify key fields are present and correct
    has_id=$(echo "$profile_body" | grep -c '"id"' || true)
    has_email=$(echo "$profile_body" | grep -c "\"$TEST_EMAIL\"" || true)
    has_display=$(echo "$profile_body" | grep -c "\"$DISPLAY_NAME\"" || true)
    no_password=$(echo "$profile_body" | grep -c '"passwordHash"' || true)

    if [ "$has_id" -gt 0 ] && [ "$has_email" -gt 0 ] && [ "$has_display" -gt 0 ] && [ "$no_password" -eq 0 ]; then
        echo "PASS: Happy path (HTTP 200, fields correct, no passwordHash exposed)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Happy path — field check failed (id=$has_id email=$has_email display=$has_display noPassword=$no_password)"
        echo "Body: $profile_body"
        FAIL=$((FAIL + 1))
    fi
fi

# Test 3: Invalid / malformed token → 401
echo ""
echo "--- Test 3: Invalid token ---"
run_test "Invalid token" 401 \
    -X GET "$BASE_URL/api/v1/auth/me" \
    -H "Authorization: Bearer not.a.valid.jwt"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
