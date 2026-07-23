#!/usr/bin/env bash
# Manual test for phone-only registration with real OTP verification (Issue #1,
# docs/issues/ISSUES.md: registration used to activate phone accounts without any
# proof of OTP verification — POST /api/v1/auth/register now requires a Firebase
# phone-auth ID token for phone-only registration, the same mechanism already used
# for phone LOGIN (see test_otp_login.sh). This script follows the same pattern.
#
# Usage:
#   FIREBASE_API_KEY=<web-api-key> BASE_URL=http://localhost:8080 bash tests/auth/test_register_phone_otp.sh
#
# QUOTA / RECAPTCHA NOTE: same as test_otp_login.sh — register a Firebase "test phone
# number" (Authentication → Sign-in method → Phone → Test phone numbers) to avoid
# consuming real SMS quota and to bypass reCAPTCHA in this script.

set -eo pipefail

FIREBASE_API_KEY="${FIREBASE_API_KEY:-}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

if [ -z "$FIREBASE_API_KEY" ]; then
    echo "ERROR: FIREBASE_API_KEY is required (Firebase Console → Project Settings → General → Web API key)."
    exit 1
fi

echo "=== Phone Registration with Firebase OTP — Manual Test ==="
echo "Target: $BASE_URL"
echo ""

read -rp "Phone number registered as a Firebase test number (E.164, e.g. +84912345678): " PHONE
TS=$(date +%s)
DISPLAY_NAME="Phone OTP Test ${TS}"
PASSWORD="TestPass1"

# ── Step 1: Ask Firebase to send OTP ─────────────────────────────────────────
echo ""
echo "--- Step 1: Requesting OTP from Firebase ---"
SEND_STATUS=$(curl -s -o /tmp/fams_reg_otp_send.json -w "%{http_code}" \
    -X POST "https://identitytoolkit.googleapis.com/v1/accounts:sendVerificationCode?key=$FIREBASE_API_KEY" \
    -H "Content-Type: application/json" \
    -d "{\"phoneNumber\":\"$PHONE\",\"recaptchaToken\":\"test-recaptcha\"}")
SEND_BODY=$(cat /tmp/fams_reg_otp_send.json)
if [ "$SEND_STATUS" -ne 200 ]; then
    fail "Firebase rejected the OTP request (HTTP $SEND_STATUS)"
    echo "Response: $SEND_BODY"
    echo "Register $PHONE as a Firebase test phone number first (see script header)."
    exit 1
fi
SESSION_INFO=$(echo "$SEND_BODY" | grep -o '"sessionInfo"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4 || true)
pass "Firebase OTP ready — enter the fixed code you set for this test number"

# ── Step 2: Enter OTP, exchange for ID token ─────────────────────────────────
echo ""
read -rp "Enter the 6-digit code: " OTP_CODE
TOKEN_STATUS=$(curl -s -o /tmp/fams_reg_otp_token.json -w "%{http_code}" \
    -X POST "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPhoneNumber?key=$FIREBASE_API_KEY" \
    -H "Content-Type: application/json" \
    -d "{\"sessionInfo\":\"$SESSION_INFO\",\"code\":\"$OTP_CODE\"}")
TOKEN_BODY=$(cat /tmp/fams_reg_otp_token.json)
if [ "$TOKEN_STATUS" -ne 200 ]; then
    fail "Firebase rejected the OTP code (HTTP $TOKEN_STATUS)"
    echo "Response: $TOKEN_BODY"
    exit 1
fi
FIREBASE_ID_TOKEN=$(echo "$TOKEN_BODY" | grep -o '"idToken"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4 || true)
pass "Firebase ID Token obtained"

# ── Step 3: Register against FAMS with the verified token ───────────────────
echo ""
echo "--- Step 3: POST /api/v1/auth/register with firebaseIdToken ---"
REG_STATUS=$(curl -s -o /tmp/fams_reg_result.json -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{
          \"phone\":           \"$PHONE\",
          \"password\":        \"$PASSWORD\",
          \"displayName\":     \"$DISPLAY_NAME\",
          \"firebaseIdToken\": \"$FIREBASE_ID_TOKEN\"
        }")
REG_BODY=$(cat /tmp/fams_reg_result.json)

if [ "$REG_STATUS" -eq 201 ]; then
    ACCESS_TOKEN=$(echo "$REG_BODY" | grep -o '"accessToken":"[^"]*"' | head -1)
    if [ -n "$ACCESS_TOKEN" ]; then
        pass "Phone registration succeeded with verified OTP (HTTP 201, tokens present)"
    else
        fail "HTTP 201 but tokens missing"
        echo "Body: $REG_BODY"
    fi
elif [ "$REG_STATUS" -eq 409 ]; then
    echo "INFO: Phone already registered from a previous run of this script (HTTP 409) — that's expected on reruns, not a failure of this feature."
else
    fail "Unexpected response (HTTP $REG_STATUS)"
    echo "Body: $REG_BODY"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
