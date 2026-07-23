#!/usr/bin/env bash
# Tests for POST /api/v1/auth/register (user registration)
# Usage: BASE_URL=http://localhost:8080 bash test_register.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

# Unique credentials per run to avoid conflicts between test runs
TS=$(date +%s)
TEST_EMAIL="testuser_${TS}@fams.com"
TEST_PHONE="+8492$(printf '%06d' $(( (TS + $$) % 1000000 )))"
TEST_NAME="Test User ${TS}"
TEST_PASS="TestPass1"

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

echo "=== Auth Register Tests ==="
echo "Target: $BASE_URL"
echo ""

# Test 1: Happy path — register with email → 201 with emailVerificationRequired=true, no tokens
echo "--- Test 1: Register with email ---"
response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASS\",\"displayName\":\"$TEST_NAME\"}")
body=$(echo "$response" | head -n -1)
status=$(echo "$response" | tail -n 1)
if [ "$status" -eq 201 ]; then
    email_verification_required=$(echo "$body" | grep -o '"emailVerificationRequired":true' | head -1)
    has_no_tokens=$(echo "$body" | grep -c '"accessToken":null\|"accessToken":""' || true)
    if [ -n "$email_verification_required" ]; then
        echo "PASS: Register with email (HTTP 201, emailVerificationRequired=true)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Register with email — HTTP 201 but emailVerificationRequired not true"
        echo "Body: $body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Register with email — expected HTTP 201, got HTTP $status"
    echo "Body: $body"
    FAIL=$((FAIL + 1))
fi

# Test 2: Duplicate email → 409
echo ""
echo "--- Test 2: Duplicate email ---"
run_test "Register - duplicate email" 409 \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASS\",\"displayName\":\"$TEST_NAME\"}"

# Test 3: Duplicate against seeded admin email → 409
echo ""
echo "--- Test 3: Existing admin email ---"
run_test "Register - existing admin email" 409 \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"TestPass1","displayName":"Admin Copy"}'

# Test 4: Phone registration WITHOUT an otpCode → 400 INVALID_ARGUMENT
# (Issue #1, docs/issues/ISSUES.md: phone registration used to activate the account
# immediately with no proof the phone number was real. Current flow: POST
# /auth/register/send-otp sends a 6-digit code — logged to the server console in
# app.sms.dev-mode — then POST /auth/register must include that code as `otpCode`.
# See tests/auth/test_register_phone_otp.sh for the full happy-path test.)
echo ""
echo "--- Test 4: Register with phone only, no otpCode → 400 INVALID_ARGUMENT ---"
response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$TEST_PHONE\",\"password\":\"$TEST_PASS\",\"displayName\":\"$TEST_NAME Phone\"}")
body=$(echo "$response" | head -n -1)
status=$(echo "$response" | tail -n 1)
if [ "$status" -eq 400 ]; then
    error_code=$(echo "$body" | grep -o '"errorCode":"INVALID_ARGUMENT"' | head -1)
    if [ -n "$error_code" ]; then
        echo "PASS: Phone registration without OTP rejected (HTTP 400, INVALID_ARGUMENT)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: HTTP 400 but errorCode is not INVALID_ARGUMENT"
        echo "Body: $body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Register with phone (no otpCode) — expected HTTP 400, got HTTP $status"
    echo "Body: $body"
    FAIL=$((FAIL + 1))
fi

# Test 4b: Phone registration with a wrong otpCode, after a real send-otp call.
echo ""
echo "--- Test 4b: Register with phone + wrong otpCode ---"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register/send-otp" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$TEST_PHONE\"}"
status4b=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$TEST_PHONE\",\"password\":\"$TEST_PASS\",\"displayName\":\"$TEST_NAME Phone\",\"otpCode\":\"000000\"}")
if [ "$status4b" -eq 400 ]; then
    echo "PASS: Wrong otpCode rejected (HTTP 400 INVALID_ARGUMENT)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Register - wrong otpCode — expected HTTP 400, got HTTP $status4b"
    FAIL=$((FAIL + 1))
fi

# Test 5: Duplicate phone → 409
# The duplicate-phone check runs before the OTP-verification check in RegisterService,
# so it fires regardless of firebaseIdToken. Pre-seed a phone-verified account directly
# via SQL (bypassing the real OTP flow, which needs a configured Firebase project) so
# this test doesn't depend on Test 4/4b having actually created an account.
echo ""
echo "--- Test 5: Duplicate phone ---"
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "INSERT INTO users (phone, password_hash, display_name, is_active, email_verified, phone_verified) \
     VALUES ('$TEST_PHONE', '\$2b\$10\$fIjK7mXIYZH8RTqSyqI2pO2rV9y45S7JS9S5530ZtMBuuCHz3Hdv6', '$TEST_NAME Seed', true, true, true) \
     ON CONFLICT (phone) DO NOTHING;" > /dev/null
run_test "Register - duplicate phone" 409 \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$TEST_PHONE\",\"password\":\"$TEST_PASS\",\"displayName\":\"Another\"}"

# Test 6: No email and no phone → 400
echo ""
echo "--- Test 6: Neither email nor phone provided ---"
run_test "Register - no email or phone" 400 \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"password\":\"$TEST_PASS\",\"displayName\":\"$TEST_NAME\"}"

# Test 7: Invalid email format → 400
echo ""
echo "--- Test 7: Invalid email format ---"
run_test "Register - invalid email" 400 \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"not-an-email\",\"password\":\"$TEST_PASS\",\"displayName\":\"$TEST_NAME\"}"

# Test 8: Password too short → 400
echo ""
echo "--- Test 8: Password too short ---"
run_test "Register - short password" 400 \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"other_${TS}@fams.com\",\"password\":\"abc\",\"displayName\":\"$TEST_NAME\"}"

# Test 9: Password missing uppercase/digit → 400
echo ""
echo "--- Test 9: Weak password (no uppercase/digit) ---"
run_test "Register - weak password" 400 \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"other2_${TS}@fams.com\",\"password\":\"alllowercase\",\"displayName\":\"$TEST_NAME\"}"

# Test 10: Missing display name → 400
echo ""
echo "--- Test 10: Missing displayName ---"
run_test "Register - missing displayName" 400 \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"other3_${TS}@fams.com\",\"password\":\"$TEST_PASS\"}"

# Test 11: Login attempt with unverified email → 403
echo ""
echo "--- Test 11: Login with unverified email → 403 ---"
run_test "Login - unverified email blocked" 403 \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASS\"}"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
