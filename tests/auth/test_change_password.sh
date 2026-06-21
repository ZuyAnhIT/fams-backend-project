#!/usr/bin/env bash
# Tests for POST /api/v1/auth/change-password
# Usage: BASE_URL=http://localhost:8080 bash test_change_password.sh

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

echo "=== Auth Change Password Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: register a test user (phone-only) and obtain a token ──────────────
TS=$(date +%s)
TEST_PHONE="+849$(printf '%07d' $(( (TS + $$) % 10000000 )))"
ORIGINAL_PASSWORD="Original@123"
NEW_PASSWORD="NewPass@456"
SECOND_NEW_PASSWORD="SecondNew@789"

echo "--- Setup: registering test user (phone=$TEST_PHONE) ---"
register_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$TEST_PHONE\",\"password\":\"$ORIGINAL_PASSWORD\",\"displayName\":\"Test User\"}")

register_body=$(echo "$register_response" | head -n -1)
register_status=$(echo "$register_response" | tail -n 1)

if [ "$register_status" -ne 201 ]; then
    echo "FAIL: Setup — could not register test user (HTTP $register_status)"
    echo "Body: $register_body"
    exit 1
fi

ACCESS_TOKEN=$(echo "$register_body" | grep -o '"accessToken":"[^"]*"' | sed 's/"accessToken":"//;s/"//')
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

# Test 7: Use new password as currentPassword to change again → 200 (verifies new password was persisted)
echo ""
echo "--- Test 7: New password accepted as currentPassword (verify change persisted) ---"
run_test "New password accepted as currentPassword" 200 \
    -X POST "$BASE_URL/api/v1/auth/change-password" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -d "{\"currentPassword\":\"$NEW_PASSWORD\",\"newPassword\":\"$SECOND_NEW_PASSWORD\"}"

# Test 8: Use original password as currentPassword → 401 (old password no longer valid)
echo ""
echo "--- Test 8: Original password rejected as currentPassword ---"
run_test "Original password rejected" 401 \
    -X POST "$BASE_URL/api/v1/auth/change-password" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -d "{\"currentPassword\":\"$ORIGINAL_PASSWORD\",\"newPassword\":\"AnyNew@999\"}"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
