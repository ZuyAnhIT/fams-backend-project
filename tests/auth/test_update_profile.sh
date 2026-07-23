#!/usr/bin/env bash
# Tests for PATCH /api/v1/auth/me
# Usage: BASE_URL=http://localhost:8080 bash test_update_profile.sh

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

echo "=== Auth Update Profile Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: register two test users (email accounts) via the login shortcut ──
# Note: this test is about PATCH /api/v1/auth/me (profile update, including its phone
# field), not about the phone-registration endpoint touched by Issue #1 — so registration
# itself just needs to hand us a token. PHONE_B below IS meaningful test data (Test 6
# asserts a phone-uniqueness conflict), so it's assigned to user B via the real
# profile-update endpoint rather than fabricated at registration time.
TS=$(date +%s)
PHONE_B="+849$(printf '%07d' $(( (TS + $$ + 1) % 10000000 )))"
PASSWORD="Profile@123"

echo "--- Setup: registering test users ---"
TOKEN_A=$(register_verified_test_user_token "$BASE_URL" "User A" "update_profile_a_${TS}_$$@fams.com" "$PASSWORD")
TOKEN_B=$(register_verified_test_user_token "$BASE_URL" "User B" "update_profile_b_${TS}_$$@fams.com" "$PASSWORD")

if [ -z "$TOKEN_A" ] || [ -z "$TOKEN_B" ]; then
    echo "FAIL: Setup — could not register test users"
    exit 1
fi

# Give user B a phone number via the real profile-update endpoint so Test 6 can verify
# the phone-uniqueness conflict check.
curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN_B" \
    -d "{\"phone\":\"$PHONE_B\"}"
echo "Setup OK"
echo ""

# Test 1: Unauthenticated → 401
echo "--- Test 1: No auth token (unauthenticated) ---"
run_test "Unauthenticated" 401 \
    -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" \
    -d '{"displayName":"New Name"}'

# Test 2: Happy path — update displayName
echo ""
echo "--- Test 2: Update displayName ---"
update_response=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN_A" \
    -d '{"displayName":"Updated Name"}')

update_body=$(echo "$update_response" | head -n -1)
update_status=$(echo "$update_response" | tail -n 1)

if [ "$update_status" -eq 200 ] && echo "$update_body" | grep -q '"Updated Name"'; then
    echo "PASS: Update displayName (HTTP 200, name updated)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Update displayName — status=$update_status body=$update_body"
    FAIL=$((FAIL + 1))
fi

# Test 3: Verify GET /me reflects the change
echo ""
echo "--- Test 3: GET /me reflects updated displayName ---"
get_response=$(curl -s "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $TOKEN_A")
if echo "$get_response" | grep -q '"Updated Name"'; then
    echo "PASS: GET /me reflects updated displayName"
    PASS=$((PASS + 1))
else
    echo "FAIL: GET /me did not reflect updated displayName. Body: $get_response"
    FAIL=$((FAIL + 1))
fi

# Test 4: Update avatarUrl
echo ""
echo "--- Test 4: Update avatarUrl ---"
run_test "Update avatarUrl" 200 \
    -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN_A" \
    -d '{"avatarUrl":"https://cdn.example.com/avatar.png"}'

# Test 5: Update phone to a new unique number
echo ""
echo "--- Test 5: Update phone to new number ---"
NEW_PHONE="+849$(printf '%07d' $(( (TS + $$ + 2) % 10000000 )))"
run_test "Update phone" 200 \
    -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN_A" \
    -d "{\"phone\":\"$NEW_PHONE\"}"

# Test 6: Conflict — phone already used by another user → 409
echo ""
echo "--- Test 6: Phone already taken by another user ---"
run_test "Phone conflict" 409 \
    -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN_A" \
    -d "{\"phone\":\"$PHONE_B\"}"

# Test 7: Validation — displayName too long → 400
echo ""
echo "--- Test 7: displayName exceeds 100 chars ---"
LONG_NAME=$(printf 'A%.0s' {1..101})
run_test "displayName too long" 400 \
    -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN_A" \
    -d "{\"displayName\":\"$LONG_NAME\"}"

# Test 8: Validation — invalid phone format → 400
echo ""
echo "--- Test 8: Invalid phone format ---"
run_test "Invalid phone format" 400 \
    -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN_A" \
    -d '{"phone":"not-a-phone"}'

# Test 9: Empty body is valid (no-op patch) → 200
echo ""
echo "--- Test 9: Empty body (no-op patch) ---"
run_test "Empty body no-op" 200 \
    -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN_A" \
    -d '{}'

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
