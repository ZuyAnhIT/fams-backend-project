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
TEST_PHONE="+8492$(echo "$TS" | tail -c 7)"
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

# Test 4: Happy path — register with phone only → 201 with tokens (no email verification needed)
echo ""
echo "--- Test 4: Register with phone only ---"
response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$TEST_PHONE\",\"password\":\"$TEST_PASS\",\"displayName\":\"$TEST_NAME Phone\"}")
body=$(echo "$response" | head -n -1)
status=$(echo "$response" | tail -n 1)
if [ "$status" -eq 201 ]; then
    access_token=$(echo "$body" | grep -o '"accessToken":"[^"]*"' | head -1)
    email_not_required=$(echo "$body" | grep -o '"emailVerificationRequired":false' | head -1)
    if [ -n "$access_token" ] && [ -n "$email_not_required" ]; then
        echo "PASS: Register with phone (HTTP 201, token present, no email verification required)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Register with phone — HTTP 201 but token missing or emailVerificationRequired not false"
        echo "Body: $body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Register with phone — expected HTTP 201, got HTTP $status"
    echo "Body: $body"
    FAIL=$((FAIL + 1))
fi

# Test 5: Duplicate phone → 409
echo ""
echo "--- Test 5: Duplicate phone ---"
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
    -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASS\"}"

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
