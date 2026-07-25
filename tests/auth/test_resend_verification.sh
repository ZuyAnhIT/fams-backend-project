#!/usr/bin/env bash
# Tests for POST /api/v1/auth/resend-verification (Issue #2, docs/issues/ISSUES.md:
# registration had no way to resend the verification email if missed/expired).
# Usage: BASE_URL=http://localhost:8080 bash test_resend_verification.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
TS=$(date +%s)
TEST_EMAIL="resend_${TS}@fams.com"

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

echo "=== Auth Resend-Verification Tests ==="
echo "Target: $BASE_URL"
echo ""

# Setup: register a fresh, unverified account to resend for
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"TestPass1\",\"displayName\":\"Resend Test\"}"

# Test 1: Resend for a real, unverified account → 200
echo "--- Test 1: Resend for unverified account ---"
run_test "Resend - unverified account" 200 \
    -X POST "$BASE_URL/api/v1/auth/resend-verification" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$TEST_EMAIL\"}"

# Test 2: Resend for a non-existent email → 200 (silent, no enumeration)
echo ""
echo "--- Test 2: Resend for non-existent email ---"
run_test "Resend - unknown email (no enumeration)" 200 \
    -X POST "$BASE_URL/api/v1/auth/resend-verification" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"definitely-not-registered-${TS}@fams.com\"}"

# Test 3: Resend for an already-verified account (seeded admin) → 200 (silent no-op)
echo ""
echo "--- Test 3: Resend for already-verified account ---"
run_test "Resend - already verified (no-op)" 200 \
    -X POST "$BASE_URL/api/v1/auth/resend-verification" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com"}'

# Test 4: Invalid email format → 400
echo ""
echo "--- Test 4: Invalid email format ---"
run_test "Resend - invalid email format" 400 \
    -X POST "$BASE_URL/api/v1/auth/resend-verification" \
    -H "Content-Type: application/json" \
    -d '{"email":"not-an-email"}'

# Test 5: Rate limit — 2 more resends (Test 1 already used attempt #1) should still be
# accepted (silent 200 either way), and the 4th total attempt must actually be suppressed
# server-side. We can't observe email delivery from a black-box HTTP test, so we check the
# rate-limit counter directly in Redis (deterministic, no log-scraping).
echo ""
echo "--- Test 5: Rate limit engages after app.email.resend-rate-limit-max attempts ---"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/resend-verification" \
    -H "Content-Type: application/json" -d "{\"email\":\"$TEST_EMAIL\"}"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/resend-verification" \
    -H "Content-Type: application/json" -d "{\"email\":\"$TEST_EMAIL\"}"
run_test "Resend - 4th attempt still returns 200 (silent even when rate-limited)" 200 \
    -X POST "$BASE_URL/api/v1/auth/resend-verification" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$TEST_EMAIL\"}"

rate_count=$(docker exec fams-redis redis-cli -a "${REDIS_PASSWORD:-redispassword123}" --no-auth-warning \
    GET "email:verify:resend:rate:${TEST_EMAIL}" 2>/dev/null || echo "")
if [ -n "$rate_count" ] && [ "$rate_count" -ge 4 ]; then
    echo "PASS: Redis rate-limit counter reached $rate_count (>= 4), server-side suppression confirmed"
    PASS=$((PASS + 1))
else
    echo "FAIL: Expected Redis rate counter >= 4, got '$rate_count'"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
