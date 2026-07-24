#!/usr/bin/env bash
# Manual test for Firebase Phone OTP Login
# POST /api/v1/auth/otp/verify
#
# Usage:
#   BASE_URL=http://localhost:8080 bash tests/auth/test_otp_login.sh
#
# Flow:
#   1. Optionally registers a FAMS account linked to your phone number.
#   2. Calls Firebase REST API to trigger an OTP SMS to your phone.
#   3. Prompts you to enter the 6-digit code you received.
#   4. Exchanges the code for a Firebase ID Token via Firebase REST API.
#   5. Sends the Firebase ID Token to POST /api/v1/auth/otp/verify.
#   6. Prints PASS if FAMS JWT tokens are returned, FAIL otherwise.
#
# QUOTA NOTE:
#   Each run triggers 1 real SMS (Firebase free tier: 10 SMS/day).
#   To avoid consuming quota during repeated tests, register your phone
#   as a "test phone number" in Firebase Console:
#     Authentication → Sign-in method → Phone → Test phone numbers
#   Set any fixed code you like — no real SMS is sent for test numbers.
#
# reCAPTCHA NOTE:
#   The Firebase REST API requires a reCAPTCHA token for real phone numbers.
#   This script sends a dummy token, which Firebase accepts only for numbers
#   registered as test phone numbers in Firebase Console.
#   For real phone numbers you must obtain the Firebase ID Token from your
#   React / React Native app and pass it directly:
#     FIREBASE_ID_TOKEN=<token> BASE_URL=http://localhost:8080 bash test_otp_login_manual.sh
#
# This script requires real Firebase credentials and/or interactive input (read -rp), so
# it's named *_manual.sh and skipped by tests/run_all.sh — see test_otp_login.sh for the
# unattended negative-path coverage of the same endpoint (no credentials needed).

set -eo pipefail

# ── Config ─────────────────────────────────────────────────────────────────────
# Set FIREBASE_API_KEY to your Firebase Web API Key (Project Settings → General → Web API key)
FIREBASE_API_KEY="${FIREBASE_API_KEY:-}"
BASE_URL="${BASE_URL:-http://localhost:8080}"

if [ -z "$FIREBASE_API_KEY" ] && [ -z "${FIREBASE_ID_TOKEN:-}" ]; then
    echo "ERROR: FIREBASE_API_KEY is required when not supplying a pre-obtained token."
    echo "  Export it before running: export FIREBASE_API_KEY=<your-web-api-key>"
    echo "  Or skip Firebase steps:   FIREBASE_ID_TOKEN=<token> bash $0"
    exit 1
fi
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "=== Firebase Phone OTP Login — Manual Test ==="
echo "Target: $BASE_URL"
echo ""

# ── Skip Firebase steps if caller already has a token ─────────────────────────
if [ -n "${FIREBASE_ID_TOKEN:-}" ]; then
    echo "Using pre-supplied FIREBASE_ID_TOKEN — skipping Firebase steps."
    echo ""
else

    # ── Step 1: Register FAMS account (if needed) ──────────────────────────────
    echo "--- Step 1: FAMS Account ---"
    read -rp "Do you already have a FAMS account linked to your phone? (y/n): " HAS_ACCOUNT

    if [[ "$HAS_ACCOUNT" != "y" && "$HAS_ACCOUNT" != "Y" ]]; then
        read -rp "Phone number (E.164, e.g. +84912345678): " PHONE
        read -rp "Email address for the new account: " EMAIL
        read -rsp "Password (min 8 chars, upper + lower + digit): " PASSWORD
        echo ""
        read -rp "Display name: " DISPLAY_NAME

        REG_STATUS=$(curl -s -o /tmp/fams_reg.json -w "%{http_code}" \
            -X POST "$BASE_URL/api/v1/auth/register" \
            -H "Content-Type: application/json" \
            -d "{
                  \"phone\":       \"$PHONE\",
                  \"email\":       \"$EMAIL\",
                  \"password\":    \"$PASSWORD\",
                  \"displayName\": \"$DISPLAY_NAME\"
                }")
        REG_BODY=$(cat /tmp/fams_reg.json)

        if [ "$REG_STATUS" -eq 201 ]; then
            pass "FAMS account registered (HTTP 201)"
        elif [ "$REG_STATUS" -eq 409 ]; then
            echo "INFO: Account already exists (HTTP 409) — continuing"
        else
            fail "Registration failed (HTTP $REG_STATUS)"
            echo "Body: $REG_BODY"
            exit 1
        fi
    else
        read -rp "Phone number (E.164, e.g. +84912345678): " PHONE
    fi

    echo ""

    # ── Step 2: Ask Firebase to send OTP ───────────────────────────────────────
    echo "--- Step 2: Sending OTP via Firebase ---"
    echo "Requesting Firebase to send OTP to $PHONE ..."

    SEND_STATUS=$(curl -s -o /tmp/fams_otp_send.json -w "%{http_code}" \
        -X POST "https://identitytoolkit.googleapis.com/v1/accounts:sendVerificationCode?key=$FIREBASE_API_KEY" \
        -H "Content-Type: application/json" \
        -d "{\"phoneNumber\":\"$PHONE\",\"recaptchaToken\":\"test-recaptcha\"}")
    SEND_BODY=$(cat /tmp/fams_otp_send.json)

    if [ "$SEND_STATUS" -ne 200 ]; then
        fail "Firebase rejected the OTP request (HTTP $SEND_STATUS)"
        echo "Response: $SEND_BODY"
        echo ""
        echo "Solutions:"
        echo "  A) Register $PHONE as a test phone in Firebase Console:"
        echo "       Authentication → Sign-in method → Phone → Test phone numbers"
        echo "  B) Get a Firebase ID Token from your React/React Native app and re-run:"
        echo "       FIREBASE_ID_TOKEN=<token> BASE_URL=$BASE_URL bash $0"
        exit 1
    fi

    SESSION_INFO=$(echo "$SEND_BODY" | grep -o '"sessionInfo"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4 || true)
    if [ -z "$SESSION_INFO" ]; then
        fail "Firebase returned HTTP 200 but no sessionInfo in response"
        echo "Body: $SEND_BODY"
        exit 1
    fi
    pass "Firebase OTP ready — enter the fixed code you set in Firebase Console"
    echo ""

    # ── Step 3: Enter OTP ──────────────────────────────────────────────────────
    echo "--- Step 3: Enter OTP ---"
    read -rp "Enter the 6-digit code: " OTP_CODE
    echo ""

    # ── Step 4: Exchange OTP for Firebase ID Token ─────────────────────────────
    echo "--- Step 4: Exchanging code with Firebase ---"
    TOKEN_STATUS=$(curl -s -o /tmp/fams_otp_token.json -w "%{http_code}" \
        -X POST "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPhoneNumber?key=$FIREBASE_API_KEY" \
        -H "Content-Type: application/json" \
        -d "{\"sessionInfo\":\"$SESSION_INFO\",\"code\":\"$OTP_CODE\"}")
    TOKEN_BODY=$(cat /tmp/fams_otp_token.json)

    if [ "$TOKEN_STATUS" -ne 200 ]; then
        fail "Firebase rejected the OTP code (HTTP $TOKEN_STATUS)"
        echo "Response: $TOKEN_BODY"
        exit 1
    fi

    FIREBASE_ID_TOKEN=$(echo "$TOKEN_BODY" | grep -o '"idToken"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4 || true)
    if [ -z "$FIREBASE_ID_TOKEN" ]; then
        fail "Firebase returned HTTP 200 but no idToken in response"
        echo "Body: $TOKEN_BODY"
        exit 1
    fi
    pass "Firebase ID Token obtained"
    echo ""

fi  # end Firebase flow

# ── Step 5: Backend verifies the Firebase ID Token ─────────────────────────────
echo "--- Step 5: Backend — POST /api/v1/auth/otp/verify ---"
LOGIN_STATUS=$(curl -s -o /tmp/fams_otp_login.json -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/otp/verify" \
    -H "Content-Type: application/json" \
    -d "{\"firebaseIdToken\":\"$FIREBASE_ID_TOKEN\",\"deviceId\":\"manual-test\"}")
LOGIN_BODY=$(cat /tmp/fams_otp_login.json)

if [ "$LOGIN_STATUS" -eq 200 ]; then
    ACCESS_TOKEN=$(echo "$LOGIN_BODY" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4 || true)
    REFRESH_TOKEN=$(echo "$LOGIN_BODY" | grep -o '"refreshToken":"[^"]*"' | cut -d'"' -f4 || true)
    if [ -n "$ACCESS_TOKEN" ] && [ -n "$REFRESH_TOKEN" ]; then
        pass "Backend login successful — access + refresh tokens received"
    else
        fail "HTTP 200 but tokens missing in response"
        echo "Body: $LOGIN_BODY"
    fi
elif [ "$LOGIN_STATUS" -eq 401 ]; then
    fail "Backend rejected the Firebase token (HTTP 401) — token invalid or phone not linked to a FAMS account"
    echo "Body: $LOGIN_BODY"
elif [ "$LOGIN_STATUS" -eq 503 ]; then
    fail "Firebase not configured on the server (HTTP 503) — check FCM_PROJECT_ID and FCM_SERVICE_ACCOUNT_JSON env vars"
    echo "Body: $LOGIN_BODY"
else
    fail "Unexpected response (HTTP $LOGIN_STATUS)"
    echo "Body: $LOGIN_BODY"
fi

# ── Summary ────────────────────────────────────────────────────────────────────
echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
