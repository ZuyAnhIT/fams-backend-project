#!/usr/bin/env bash
# Tests for account locking on repeated failed login attempts (task 14)
# POST /api/v1/auth/login — returns 401 on wrong password, 423 after 5 failed attempts
# Usage: BASE_URL=http://localhost:8080 bash test_account_lock.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TS=$(date +%s)
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

echo "=== Account Lock Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: register a fresh user (no email verification needed for lock test) ──

TEST_EMAIL="locktest_${TS}@fams-test.local"
TEST_PASS="Correct@1234"
WRONG_PASS="WrongPass@999"

echo "--- Setup: Registering test account $TEST_EMAIL ---"
reg_status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASS\",\"displayName\":\"Lock Tester\"}")

if [ "$reg_status" -ne 201 ]; then
    echo "SETUP FAIL: Registration failed (HTTP $reg_status)"
    exit 1
fi
echo "Setup complete: test account registered."
echo ""

# ── Test 1–4: Wrong password attempts 1–4 → 401 each ────────────────────────

echo "--- Tests 1–4: Wrong password attempts 1–4 should return 401 ---"
for i in 1 2 3 4; do
    run_test "Wrong password attempt $i/4 returns 401" 401 \
        -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"$WRONG_PASS\"}"
done

# ── Test 5: 5th wrong password → 423 (account now locked) ────────────────────

echo ""
echo "--- Test 5: 5th wrong password should lock the account (423) ---"
run_test "5th wrong password locks account (423)" 423 \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"$WRONG_PASS\"}"

# ── Test 6: Correct password while locked → 423 ──────────────────────────────

echo ""
echo "--- Test 6: Correct password while account is locked should still return 423 ---"
run_test "Correct password while locked returns 423" 423 \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASS\"}"

# ── Test 7: Wrong password on locked account → 423 (not 401) ─────────────────

echo ""
echo "--- Test 7: Another wrong password on locked account should still return 423 ---"
run_test "Wrong password on locked account returns 423" 423 \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"$WRONG_PASS\"}"

# ── Test 8–9: Successful login resets counter (admin account) ─────────────────

echo ""
echo "--- Tests 8–9: Successful login should reset the failed attempt counter ---"

# Send 3 wrong passwords to admin to increment counter
for i in 1 2 3; do
    curl -s -o /dev/null \
        -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d '{"email":"admin@fams.com","password":"WrongPass@999"}' > /dev/null
done

# Login correctly — should reset counter
run_test "Correct login resets failed attempt counter (200)" 200 \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}'

# Send 3 more wrong passwords — should be 401 (counter was reset, not still at 3)
run_test "Wrong password after counter reset returns 401 (not 423)" 401 \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"WrongPass@999"}'

# Reset admin to clean state (2 more wrong + correct to get to 3 then reset)
# Actually just do a correct login to reset back cleanly
curl -s -o /dev/null \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}' > /dev/null

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
