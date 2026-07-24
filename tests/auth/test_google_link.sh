#!/usr/bin/env bash
# Tests for Issue #7 (docs/issues/ISSUES.md): two-way Google account linking.
# (a) A normal email+password user can explicitly connect a Google account.
# (b) A Google-only user (no password ever set) can use forgot-password to set one and
#     start logging in normally — this already worked via existing PasswordResetService
#     code with zero changes needed (BCryptPasswordEncoder.matches() safely returns false
#     for a null stored hash rather than throwing); verified here as a regression guard.
# Usage: BASE_URL=http://localhost:8080 bash test_google_link.sh
#
# Note: a REAL Google ID token can't be produced by a shell script (requires the actual
# Google OAuth client flow — see /google-login-test.html for the manual end-to-end test of
# POST /auth/login/google and /auth/link-google's happy path). This script covers
# everything that doesn't require a real Google token: validation, guards, and the
# already-working forgot-password-for-Google-only-accounts path end-to-end.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
TS=$(date +%s)

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "=== Google Account Linking Tests (Issue #7) ==="
echo "Target: $BASE_URL"
echo ""

EMAIL="google_link_${TS}@fams.com"
TOKEN=$(register_verified_test_user_token "$BASE_URL" "Google Link Test" "$EMAIL")

# Test 1: Fresh profile shows googleLinked=false
echo "--- Test 1: New account has googleLinked=false ---"
linked=$(curl -s -X GET "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $TOKEN" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['googleLinked'])")
if [ "$linked" = "False" ]; then
    pass "googleLinked=false for a normal email/password account"
else
    fail "Expected googleLinked=false, got $linked"
fi

# Test 2: link-google with an invalid/garbage token → 401
echo ""
echo "--- Test 2: link-google with garbage idToken rejected ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/link-google" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"idToken":"garbage"}')
if [ "$status" -eq 401 ]; then
    pass "Invalid Google ID token rejected (HTTP 401)"
else
    fail "Expected 401, got $status"
fi

# Test 3: link-google/unlink-google require authentication
echo ""
echo "--- Test 3: link-google and unlink-google require auth ---"
s1=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/link-google" \
    -H "Content-Type: application/json" -d '{"idToken":"x"}')
s2=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/unlink-google")
if [ "$s1" -eq 401 ] && [ "$s2" -eq 401 ]; then
    pass "Both endpoints reject unauthenticated requests"
else
    fail "Expected both 401, got link=$s1 unlink=$s2"
fi

# Test 4: unlink-google succeeds (no-op) for an account that has a password but no Google linked
echo ""
echo "--- Test 4: unlink-google is a safe no-op when nothing is linked (password present) ---"
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/unlink-google" \
    -H "Authorization: Bearer $TOKEN")
if [ "$status" -eq 200 ]; then
    pass "unlink-google succeeds harmlessly when there was nothing linked"
else
    fail "Expected 200, got $status"
fi

# Test 5: unlink-google is BLOCKED for a Google-only account (no password set) — this is
# the safety guard preventing an account from ending up with zero sign-in methods.
echo ""
echo "--- Test 5: unlink-google blocked when no password is set ---"
docker exec fams-postgres psql -U fams_user -d fams_db -c \
    "INSERT INTO users (email, google_id, display_name, is_active, email_verified) \
     VALUES ('google_only_${TS}@fams.com', 'fake-google-id-${TS}', 'Google Only Test', true, true) \
     ON CONFLICT (email) DO NOTHING;" > /dev/null
GOOGLE_ONLY_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT id FROM users WHERE email='google_only_${TS}@fams.com';" | tr -d ' ')
GOOGLE_ONLY_TOKEN=$(python3 -c "
import hmac, hashlib, base64, json, time
def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=')
header = b64url(json.dumps({'alg':'HS256'}).encode())
payload = b64url(json.dumps({
    'sub': '${GOOGLE_ONLY_ID}', 'email': 'google_only_${TS}@fams.com', 'deviceId': 'test',
    'isPlatformAdmin': False, 'iat': int(time.time()), 'exp': int(time.time()) + 900
}).encode())
msg = header + b'.' + payload
sig = b64url(hmac.new(b'supersecretjwtkey123456789012345678901234567890', msg, hashlib.sha256).digest())
print((msg + b'.' + sig).decode())
")
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/unlink-google" \
    -H "Authorization: Bearer $GOOGLE_ONLY_TOKEN")
if [ "$status" -eq 400 ]; then
    pass "unlink-google blocked for a password-less (Google-only) account (HTTP 400)"
else
    fail "Expected 400, got $status"
fi

# Test 6 (regression guard): a Google-only account can set a password via forgot-password,
# then log in normally with email+password afterward. This code path already existed and
# already worked before Issue #7 — verified end-to-end here so a future change can't
# silently break it.
echo ""
echo "--- Test 6: Google-only account sets a password via forgot-password, then logs in normally ---"
GOOGLE_ONLY_EMAIL="google_only_${TS}@fams.com"

# Sanity: normal login with a guessed password must NOT crash (null passwordHash) — 401, not 500
login_attempt_status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" -d "{\"identifier\":\"$GOOGLE_ONLY_EMAIL\",\"password\":\"WhateverPass1\"}")
if [ "$login_attempt_status" -ne 401 ]; then
    fail "Login attempt on Google-only account before password is set — expected 401, got $login_attempt_status (possible null-password crash)"
else
    pass "Login attempt on Google-only account (no password yet) safely returns 401, no crash"
fi

curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/forgot-password" -H "Content-Type: application/json" \
    -d "{\"email\":\"$GOOGLE_ONLY_EMAIL\"}"
# Scoped to GOOGLE_ONLY_ID (the token's Redis value), not just "last key created" —
# a bare `KEYS ... | tail -1` can grab a DIFFERENT test's in-flight reset token (e.g.
# admin@fams.com's, from test_forgot_reset_password.sh running around the same time),
# silently resetting the wrong account's password. Bit us for real once already.
RESET_TOKEN=""
for key in $(docker exec fams-redis redis-cli -a "${REDIS_PASSWORD:-redispassword123}" --no-auth-warning \
    KEYS "pwd:reset:token:*"); do
    value=$(docker exec fams-redis redis-cli -a "${REDIS_PASSWORD:-redispassword123}" --no-auth-warning GET "$key" | tr -d '\r')
    if [ "$value" = "$GOOGLE_ONLY_ID" ]; then
        RESET_TOKEN=$(echo "$key" | sed 's/^pwd:reset:token://')
        break
    fi
done

if [ -n "$RESET_TOKEN" ]; then
    reset_status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/reset-password" \
        -H "Content-Type: application/json" -d "{\"token\":\"$RESET_TOKEN\",\"newPassword\":\"NewPass123\"}")
    login_status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" -d "{\"identifier\":\"$GOOGLE_ONLY_EMAIL\",\"password\":\"NewPass123\"}")
    if [ "$reset_status" -eq 200 ] && [ "$login_status" -eq 200 ]; then
        pass "Google-only account set a password via forgot-password and logged in normally afterward"
    else
        fail "reset_status=$reset_status login_status=$login_status"
    fi
else
    fail "Could not find password reset token in Redis"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
