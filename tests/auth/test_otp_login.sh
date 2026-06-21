#!/usr/bin/env bash
# Tests for Phone OTP Login
# POST /api/v1/auth/otp/send  and  POST /api/v1/auth/otp/verify
# Usage: BASE_URL=http://localhost:8080 OTP_DEV_FIXED_CODE=123456 bash test_otp_login.sh

set -euo pipefail

echo "SKIPPED: OTP/SMS tests disabled (SMS service not configured in test environment)"
exit 0

BASE_URL="${BASE_URL:-http://localhost:8080}"
TEST_PHONE="${TEST_PHONE:-+84900000000}"
OTP_CODE="${OTP_DEV_FIXED_CODE:-123456}"
PASS=0
FAIL=0

# Unique phone per run for the rate-limit test so counters don't accumulate across runs
RATE_PHONE="+8490$(date +%s | tail -c 7)"
# Must match OTP_RATE_LIMIT_MAX set in the server's environment
RATE_LIMIT="${OTP_RATE_LIMIT_MAX:-3}"

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

echo "=== Auth OTP Login Tests ==="
echo "Target: $BASE_URL"
echo ""

# Test 1: Send OTP to valid phone → 200
echo "--- Test 1: Send OTP to known phone ---"
run_test "Send OTP - known phone" 200 \
    -X POST "$BASE_URL/api/v1/auth/otp/send" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$TEST_PHONE\"}"

# Test 2: Send OTP to unknown phone → 200 (no enumeration)
echo ""
echo "--- Test 2: Send OTP to unknown phone (no enumeration) ---"
run_test "Send OTP - unknown phone" 200 \
    -X POST "$BASE_URL/api/v1/auth/otp/send" \
    -H "Content-Type: application/json" \
    -d '{"phone":"+84900099999"}'

# Test 3: Missing phone field → 400
echo ""
echo "--- Test 3: Missing phone ---"
run_test "Send OTP - missing phone" 400 \
    -X POST "$BASE_URL/api/v1/auth/otp/send" \
    -H "Content-Type: application/json" \
    -d '{}'

# Test 4: Invalid phone format → 400
echo ""
echo "--- Test 4: Invalid phone format ---"
run_test "Send OTP - invalid phone" 400 \
    -X POST "$BASE_URL/api/v1/auth/otp/send" \
    -H "Content-Type: application/json" \
    -d '{"phone":"not-a-phone"}'

# Test 5: Verify OTP - happy path → 200 with tokens
echo ""
echo "--- Test 5: Verify OTP - happy path ---"
response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/otp/verify" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$TEST_PHONE\",\"code\":\"$OTP_CODE\",\"deviceId\":\"test-device\"}")

body=$(echo "$response" | head -n -1)
status=$(echo "$response" | tail -n 1)

if [ "$status" -eq 200 ]; then
    access_token=$(echo "$body" | grep -o '"accessToken":"[^"]*"' | head -1)
    refresh_token=$(echo "$body" | grep -o '"refreshToken":"[^"]*"' | head -1)
    if [ -n "$access_token" ] && [ -n "$refresh_token" ]; then
        echo "PASS: Verify OTP happy path (HTTP 200, tokens present)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Verify OTP — HTTP 200 but tokens missing"
        echo "Body: $body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Verify OTP — expected HTTP 200, got HTTP $status"
    echo "Body: $body"
    FAIL=$((FAIL + 1))
fi

# Test 6: Verify wrong OTP → 401
echo ""
echo "--- Test 6: Verify wrong OTP ---"
run_test "Verify OTP - wrong code" 401 \
    -X POST "$BASE_URL/api/v1/auth/otp/verify" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$TEST_PHONE\",\"code\":\"000000\"}"

# Test 7: Missing code → 400
echo ""
echo "--- Test 7: Verify OTP - missing code ---"
run_test "Verify OTP - missing code" 400 \
    -X POST "$BASE_URL/api/v1/auth/otp/verify" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$TEST_PHONE\"}"

# Test 8: Rate limit — use a unique phone per run (fresh counter).
# Send exactly RATE_LIMIT times to exhaust the window, then the next must return 429.
echo ""
echo "--- Test 8: Rate limit enforcement (limit=$RATE_LIMIT) ---"
for _i in $(seq 1 "$RATE_LIMIT"); do
    curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/otp/send" \
        -H "Content-Type: application/json" \
        -d "{\"phone\":\"$RATE_PHONE\"}"
done
run_test "Rate limit hit" 429 \
    -X POST "$BASE_URL/api/v1/auth/otp/send" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$RATE_PHONE\"}"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
