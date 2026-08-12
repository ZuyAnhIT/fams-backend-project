#!/usr/bin/env bash
# Tests for POST /api/v1/auth/forgot-password and POST /api/v1/auth/reset-password
# Usage: BASE_URL=http://localhost:8080 bash test_forgot_reset_password.sh

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

echo "=== Forgot / Reset Password Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── forgot-password ───────────────────────────────────────────────

# Test 1: Known email → 200 (generic message, no info leak)
echo "--- Test 1: Forgot password with known email ---"
run_test "Forgot password - known email" 200 \
    -X POST "$BASE_URL/api/v1/auth/forgot-password" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com"}'

# Test 2: Unknown email → 200 (generic message — must not reveal whether email exists)
echo ""
echo "--- Test 2: Forgot password with unknown email ---"
run_test "Forgot password - unknown email (no info leak)" 200 \
    -X POST "$BASE_URL/api/v1/auth/forgot-password" \
    -H "Content-Type: application/json" \
    -d '{"email":"nobody@nowhere.com"}'

# Test 3: Invalid email format → 400
echo ""
echo "--- Test 3: Forgot password - invalid email format ---"
run_test "Forgot password - invalid email format" 400 \
    -X POST "$BASE_URL/api/v1/auth/forgot-password" \
    -H "Content-Type: application/json" \
    -d '{"email":"not-an-email"}'

# Test 4: Missing email → 400
echo ""
echo "--- Test 4: Forgot password - missing email ---"
run_test "Forgot password - missing email" 400 \
    -X POST "$BASE_URL/api/v1/auth/forgot-password" \
    -H "Content-Type: application/json" \
    -d '{}'

# ── reset-password ────────────────────────────────────────────────

# Test 5: Invalid / expired token → 400
echo ""
echo "--- Test 5: Reset password - invalid token ---"
run_test "Reset password - invalid token" 400 \
    -X POST "$BASE_URL/api/v1/auth/reset-password" \
    -H "Content-Type: application/json" \
    -d '{"token":"00000000-0000-0000-0000-000000000000","newPassword":"NewPass1"}'

# Test 6: Missing token → 400
echo ""
echo "--- Test 6: Reset password - missing token ---"
run_test "Reset password - missing token" 400 \
    -X POST "$BASE_URL/api/v1/auth/reset-password" \
    -H "Content-Type: application/json" \
    -d '{"newPassword":"NewPass1"}'

# Test 7: Missing newPassword → 400
echo ""
echo "--- Test 7: Reset password - missing newPassword ---"
run_test "Reset password - missing newPassword" 400 \
    -X POST "$BASE_URL/api/v1/auth/reset-password" \
    -H "Content-Type: application/json" \
    -d '{"token":"some-token"}'

# Test 8: Weak password (no uppercase) → 400
echo ""
echo "--- Test 8: Reset password - weak password ---"
run_test "Reset password - weak password" 400 \
    -X POST "$BASE_URL/api/v1/auth/reset-password" \
    -H "Content-Type: application/json" \
    -d '{"token":"some-token","newPassword":"alllower1"}'

# Test 9: Password too short → 400
echo ""
echo "--- Test 9: Reset password - short password ---"
run_test "Reset password - short password" 400 \
    -X POST "$BASE_URL/api/v1/auth/reset-password" \
    -H "Content-Type: application/json" \
    -d '{"token":"some-token","newPassword":"Ab1"}'

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
