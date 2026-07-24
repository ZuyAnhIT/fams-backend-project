#!/usr/bin/env bash
# Tests for POST /api/v1/auth/change-password
# Usage: BASE_URL=http://localhost:8080 bash test_change_password.sh

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

echo "=== Auth Change Password Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: register a test user (email) and obtain a token ───────────────────
TS=$(date +%s)
TEST_EMAIL="changepass_${TS}_$$@fams.com"
ORIGINAL_PASSWORD="Original@123"
NEW_PASSWORD="NewPass@456"
SECOND_NEW_PASSWORD="SecondNew@789"

echo "--- Setup: registering test user (email=$TEST_EMAIL) ---"
ACCESS_TOKEN=$(register_verified_test_user_token "$BASE_URL" "Test User" "$TEST_EMAIL" "$ORIGINAL_PASSWORD")
if [ -z "$ACCESS_TOKEN" ]; then
    echo "FAIL: Setup — could not extract access token"
    exit 1
fi
echo "Setup OK — token acquired"
echo ""

# Test 1: Unauthenticated request → 401
echo "--- Test 1: No auth token (unauthenticated) ---"
run_test "Unauthenticated" 401 \
    -X POST "$BASE_URL/api/v1/auth/change-password" \
    -H "Content-Type: application/json" \
    -d "{\"currentPassword\":\"$ORIGINAL_PASSWORD\",\"newPassword\":\"$NEW_PASSWORD\"}"

# Test 2: Wrong current password → 401
echo ""
echo "--- Test 2: Wrong current password ---"
run_test "Wrong current password" 401 \
    -X POST "$BASE_URL/api/v1/auth/change-password" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -d "{\"currentPassword\":\"WrongPass@999\",\"newPassword\":\"$NEW_PASSWORD\"}"

# Test 3: New password too weak (no uppercase) → 400
echo ""
echo "--- Test 3: Weak new password (no uppercase) ---"
run_test "Weak new password" 400 \
    -X POST "$BASE_URL/api/v1/auth/change-password" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -d "{\"currentPassword\":\"$ORIGINAL_PASSWORD\",\"newPassword\":\"weakpass1\"}"

# Test 4: Missing currentPassword → 400
echo ""
echo "--- Test 4: Missing currentPassword field ---"
run_test "Missing currentPassword" 400 \
    -X POST "$BASE_URL/api/v1/auth/change-password" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -d "{\"newPassword\":\"$NEW_PASSWORD\"}"

# Test 5: Missing newPassword → 400
echo ""
echo "--- Test 5: Missing newPassword field ---"
run_test "Missing newPassword" 400 \
    -X POST "$BASE_URL/api/v1/auth/change-password" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -d "{\"currentPassword\":\"$ORIGINAL_PASSWORD\"}"

# Test 6: Happy path — correct current password → 200
echo ""
echo "--- Test 6: Happy path (correct current password) ---"
run_test "Happy path" 200 \
    -X POST "$BASE_URL/api/v1/auth/change-password" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -d "{\"currentPassword\":\"$ORIGINAL_PASSWORD\",\"newPassword\":\"$NEW_PASSWORD\"}"

# Test 7: The access token used to make the change is immediately dead — not just refresh
# tokens revoked, the ACCESS token itself must stop working right away (not wait out its
# remaining TTL), otherwise a leaked/stale token would keep granting access after the user
# changed password specifically because they suspected it was compromised.
echo ""
echo "--- Test 7: Access token used for the change is invalidated immediately ---"
run_test "Old access token rejected immediately after change" 401 \
    -X GET "$BASE_URL/api/v1/auth/me" \
    -H "Authorization: Bearer $ACCESS_TOKEN"

# Test 8: Old password no longer works — login with a fresh token using the new password
echo ""
echo "--- Test 8: Login with new password (old token is dead, must re-authenticate) ---"
# Regression guard: the fresh login must work immediately, even inside the same wall-clock
# second as the revoke event. Access tokens carry issuedAtMillis for this distinction.
login_response=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$TEST_EMAIL\",\"password\":\"$NEW_PASSWORD\"}")
ACCESS_TOKEN_2=$(echo "$login_response" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -n "$ACCESS_TOKEN_2" ]; then
    echo "PASS: Login with new password succeeds, fresh token obtained"
    PASS=$((PASS + 1))
else
    echo "FAIL: Could not log in with new password. Body: $login_response"
    FAIL=$((FAIL + 1))
fi

# Test 9: With the fresh token, change again — verifies new password was actually persisted
# (not just that the old token died) and that the endpoint still works on a second call.
echo ""
echo "--- Test 9: Change password again with fresh token (verify persisted, still works) ---"
run_test "Second change with fresh token" 200 \
    -X POST "$BASE_URL/api/v1/auth/change-password" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ACCESS_TOKEN_2" \
    -d "{\"currentPassword\":\"$NEW_PASSWORD\",\"newPassword\":\"$SECOND_NEW_PASSWORD\"}"

# Test 10: Original (pre-change) password rejected on login — old password no longer valid
echo ""
echo "--- Test 10: Original password rejected on login ---"
run_test "Original password rejected on login" 401 \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$TEST_EMAIL\",\"password\":\"$ORIGINAL_PASSWORD\"}"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
