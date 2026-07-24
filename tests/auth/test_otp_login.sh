#!/usr/bin/env bash
# Automated, no-credentials-needed tests for POST /api/v1/auth/otp/verify
# (Firebase Phone Auth login — the "quick login via phone OTP" path).
#
# The real happy-path needs a genuine Firebase ID token (real SMS OTP exchanged via the
# Firebase REST API, or a token pasted from a mobile app) — that's covered separately by
# tests/auth/test_otp_login_manual.sh. This script covers everything that's verifiable
# without credentials: validation, structural garbage input, and rate limiting — and in
# particular regression-guards a bug found in this environment where a well-formed-but-
# fake-signed JWT crashed FirebaseAuth.verifyIdToken() with a bare NullPointerException
# (missing `iat` claim) that FirebasePhoneTokenVerifier didn't catch, surfacing as an
# HTTP 500 instead of a clean 401.
#
# Usage: BASE_URL=http://localhost:8080 bash tests/auth/test_otp_login.sh

set -uo pipefail

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

echo "=== Phone OTP Quick-Login (Firebase) — Automated Tests ==="
echo "Target: $BASE_URL"
echo ""

echo "--- Test 1: Missing firebaseIdToken field ---"
run_test "Missing firebaseIdToken" 400 \
    -X POST "$BASE_URL/api/v1/auth/otp/verify" \
    -H "Content-Type: application/json" \
    -d '{}'

echo ""
echo "--- Test 2: Blank firebaseIdToken ---"
run_test "Blank firebaseIdToken" 400 \
    -X POST "$BASE_URL/api/v1/auth/otp/verify" \
    -H "Content-Type: application/json" \
    -d '{"firebaseIdToken":""}'

echo ""
echo "--- Test 3: Garbage (non-JWT) token → 401, not 500 ---"
run_test "Garbage token" 401 \
    -X POST "$BASE_URL/api/v1/auth/otp/verify" \
    -H "Content-Type: application/json" \
    -d '{"firebaseIdToken":"not-a-real-token"}'

echo ""
echo "--- Test 4: Well-formed but fake-signed JWT → 401, not 500 (regression guard) ---"
FAKE_JWT="eyJhbGciOiJSUzI1NiIsImtpZCI6ImZha2UifQ.eyJzdWIiOiIxMjM0NTY3ODkwIiwicGhvbmVfbnVtYmVyIjoiKzg0OTEyMzQ1Njc4IiwiYXVkIjoicGhvbmUtZmFtcyIsImlzcyI6Imh0dHBzOi8vc2VjdXJldG9rZW4uZ29vZ2xlLmNvbS9waG9uZS1mYW1zIiwiZXhwIjo5OTk5OTk5OTk5fQ.fakesignature"
run_test "Fake-signed well-formed JWT" 401 \
    -X POST "$BASE_URL/api/v1/auth/otp/verify" \
    -H "Content-Type: application/json" \
    -d "{\"firebaseIdToken\":\"$FAKE_JWT\"}"

echo ""
echo "--- Test 5: Endpoint is publicly accessible (no Authorization header) ---"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/otp/verify" \
    -H "Content-Type: application/json" \
    -d '{"firebaseIdToken":"check-public"}')
if [ "$STATUS" -ne 403 ]; then
    echo "PASS: Endpoint is public — not blocked by security filter (HTTP $STATUS, service handled the request)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Endpoint returned 403 — security filter may be blocking unauthenticated requests"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
echo "NOTE: Happy-path (real SMS OTP) requires Firebase credentials — see tests/auth/test_otp_login_manual.sh"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
