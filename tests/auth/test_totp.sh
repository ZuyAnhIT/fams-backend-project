#!/usr/bin/env bash
# Tests for TOTP 2FA setup endpoints
# POST /api/v1/auth/totp/setup
# GET  /api/v1/auth/totp/qr
# POST /api/v1/auth/totp/verify
# Usage: BASE_URL=http://localhost:8080 bash test_totp.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
TS=$(date +%s)

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

echo "=== TOTP 2FA Setup Tests ==="
echo "Target: $BASE_URL"
echo ""

# Register a test user
ACCESS_TOKEN=$(register_verified_test_user_token "$BASE_URL" "TOTP Tester")
if [ -z "$ACCESS_TOKEN" ]; then
    echo "SETUP FAIL: Could not extract access token"
    exit 1
fi
echo "Test user registered. Access token obtained."
echo ""

# ── totp/setup ────────────────────────────────────────────────────

# Test 1: Unauthenticated setup → 401
echo "--- Test 1: Setup without auth ---"
run_test "TOTP setup - unauthenticated" 401 \
    -X POST "$BASE_URL/api/v1/auth/totp/setup"

# Test 2: Authenticated setup → 200 with setupToken + qrCodeUrl
echo ""
echo "--- Test 2: Authenticated TOTP setup ---"
setup_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/totp/setup" \
    -H "Authorization: Bearer $ACCESS_TOKEN")
setup_body=$(echo "$setup_response" | head -n -1)
setup_status=$(echo "$setup_response" | tail -n 1)

SETUP_TOKEN=""
QR_URL=""

if [ "$setup_status" -eq 200 ]; then
    SETUP_TOKEN=$(echo "$setup_body" | grep -o '"setupToken":"[^"]*"' | head -1 | cut -d'"' -f4)
    QR_URL=$(echo "$setup_body" | grep -o '"qrCodeUrl":"[^"]*"' | head -1 | cut -d'"' -f4)
    MANUAL_KEY=$(echo "$setup_body" | grep -o '"manualEntryKey":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ -n "$SETUP_TOKEN" ] && [ -n "$QR_URL" ] && [ -n "$MANUAL_KEY" ]; then
        echo "PASS: TOTP setup (HTTP 200, setupToken + qrCodeUrl + manualEntryKey present)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: HTTP 200 but response fields missing"
        echo "Body: $setup_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: TOTP setup — expected HTTP 200, got HTTP $setup_status"
    echo "Body: $setup_body"
    FAIL=$((FAIL + 1))
fi

# ── totp/qr ───────────────────────────────────────────────────────

# Test 3: QR page with valid setup token → 200 HTML
echo ""
echo "--- Test 3: QR code page with valid token ---"
if [ -n "$SETUP_TOKEN" ]; then
    qr_status=$(curl -s -o /dev/null -w "%{http_code}" \
        "$BASE_URL/api/v1/auth/totp/qr?token=$SETUP_TOKEN")
    if [ "$qr_status" -eq 200 ]; then
        echo "PASS: QR page (HTTP 200)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: QR page — expected HTTP 200, got HTTP $qr_status"
        FAIL=$((FAIL + 1))
    fi
else
    echo "SKIP: QR page test skipped (no setup token from previous test)"
    FAIL=$((FAIL + 1))
fi

# Test 4: QR page with invalid token → 400
echo ""
echo "--- Test 4: QR code page with invalid token ---"
run_test "TOTP QR - invalid token" 400 \
    "$BASE_URL/api/v1/auth/totp/qr?token=00000000-0000-0000-0000-000000000000"

# ── totp/verify ───────────────────────────────────────────────────

# Test 5: Unauthenticated verify → 401
echo ""
echo "--- Test 5: Verify without auth ---"
run_test "TOTP verify - unauthenticated" 401 \
    -X POST "$BASE_URL/api/v1/auth/totp/verify" \
    -H "Content-Type: application/json" \
    -d '{"setupToken":"some-token","code":"123456"}'

# Test 6: Verify with invalid/expired setup token → 400
echo ""
echo "--- Test 6: Verify with invalid setup token ---"
run_test "TOTP verify - invalid setup token" 400 \
    -X POST "$BASE_URL/api/v1/auth/totp/verify" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"setupToken":"00000000-0000-0000-0000-000000000000","code":"123456"}'

# Test 7: Verify with wrong TOTP code (valid token but wrong code) → 400
echo ""
echo "--- Test 7: Verify with wrong TOTP code ---"
if [ -n "$SETUP_TOKEN" ]; then
    run_test "TOTP verify - wrong code" 400 \
        -X POST "$BASE_URL/api/v1/auth/totp/verify" \
        -H "Authorization: Bearer $ACCESS_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"setupToken\":\"$SETUP_TOKEN\",\"code\":\"000000\"}"
else
    echo "SKIP: No setup token available"
    FAIL=$((FAIL + 1))
fi

# Test 8: Verify with invalid code format (non-digit) → 400
echo ""
echo "--- Test 8: Verify with non-digit code ---"
run_test "TOTP verify - non-digit code" 400 \
    -X POST "$BASE_URL/api/v1/auth/totp/verify" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"setupToken\":\"${SETUP_TOKEN:-dummy}\",\"code\":\"abcdef\"}"

# Test 9: Verify with code too short → 400
echo ""
echo "--- Test 9: Verify with short code ---"
run_test "TOTP verify - short code" 400 \
    -X POST "$BASE_URL/api/v1/auth/totp/verify" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"setupToken\":\"${SETUP_TOKEN:-dummy}\",\"code\":\"123\"}"

# Test 10: Missing setupToken → 400
echo ""
echo "--- Test 10: Verify with missing setupToken ---"
run_test "TOTP verify - missing setupToken" 400 \
    -X POST "$BASE_URL/api/v1/auth/totp/verify" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"code":"123456"}'

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
