#!/usr/bin/env bash
# Full happy-path test for phone-only registration via the in-house OTP flow
# (RegisterService.sendRegistrationOtp / register + PhoneOtp + SmsService).
#
# This replaced the earlier Firebase-Phone-Auth-based registration flow. In
# app.sms.dev-mode (default true, see application.yml / SmsService), the OTP is
# logged to the API container's console instead of being sent as a real SMS, so
# this script can run fully automated — no Firebase project or manual code entry
# required.
#
# Usage:
#   BASE_URL=http://localhost:8080 bash tests/auth/test_register_phone_otp.sh
#
# Requires: the API container running as `fams-api` under `docker compose`
# (used to scrape the OTP from `docker compose logs`).

set -eo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "=== Phone Registration via in-house OTP — Automated Test ==="
echo "Target: $BASE_URL"
echo ""

TS=$(date +%s)
PHONE="+8493$(printf '%07d' $(( (TS + $$) % 10000000 )))"
DISPLAY_NAME="Phone OTP Test ${TS}"
PASSWORD="TestPass1"

fetch_otp() {
    # Scrapes the most recent "[DEV] SMS OTP for <phone>" block from the API
    # container's logs and prints the 6-digit code.
    docker compose logs fams-api --since 30s 2>/dev/null \
        | grep -A1 "\[DEV\] SMS OTP for ${PHONE} " \
        | grep -o "OTP CODE: [0-9]\{6\}" \
        | tail -1 \
        | grep -o "[0-9]\{6\}"
}

# ── Step 1: request OTP ──────────────────────────────────────────────────────
echo "--- Step 1: POST /auth/register/send-otp ---"
SEND_STATUS=$(curl -s -o /tmp/fams_reg_otp_send.json -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register/send-otp" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$PHONE\"}")
if [ "$SEND_STATUS" -eq 200 ]; then
    pass "OTP send accepted (HTTP 200)"
else
    fail "OTP send rejected (HTTP $SEND_STATUS): $(cat /tmp/fams_reg_otp_send.json)"
    echo ""; echo "=== Results ==="; echo "PASSED: $PASS"; echo "FAILED: $FAIL"
    exit 1
fi

sleep 1
OTP_CODE=$(fetch_otp)
if [ -z "$OTP_CODE" ]; then
    fail "Could not read OTP from 'docker compose logs fams-api' — is app.sms.dev-mode=true, and is the container named fams-api?"
    echo ""; echo "=== Results ==="; echo "PASSED: $PASS"; echo "FAILED: $FAIL"
    exit 1
fi
pass "OTP scraped from dev-mode server log ($OTP_CODE)"

# ── Step 2: wrong OTP is rejected first ──────────────────────────────────────
echo ""
echo "--- Step 2: register with a deliberately wrong otpCode → 400 ---"
WRONG_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$PHONE\",\"password\":\"$PASSWORD\",\"displayName\":\"$DISPLAY_NAME\",\"otpCode\":\"000000\"}")
if [ "$WRONG_STATUS" -eq 400 ]; then
    pass "Wrong OTP rejected (HTTP 400)"
else
    fail "Wrong OTP — expected HTTP 400, got HTTP $WRONG_STATUS"
fi

# ── Step 3: register with the real OTP ───────────────────────────────────────
echo ""
echo "--- Step 3: POST /auth/register with the real otpCode ---"
REG_STATUS=$(curl -s -o /tmp/fams_reg_result.json -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$PHONE\",\"password\":\"$PASSWORD\",\"displayName\":\"$DISPLAY_NAME\",\"otpCode\":\"$OTP_CODE\"}")
REG_BODY=$(cat /tmp/fams_reg_result.json)

if [ "$REG_STATUS" -eq 201 ]; then
    phone_verified=$(echo "$REG_BODY" | grep -o '"phoneVerified":true' | head -1)
    if [ -n "$phone_verified" ]; then
        pass "Phone registration succeeded with verified OTP (HTTP 201, phoneVerified=true)"
    else
        fail "HTTP 201 but phoneVerified is not true: $REG_BODY"
    fi
else
    fail "Register with real OTP — expected HTTP 201, got HTTP $REG_STATUS: $REG_BODY"
fi

# ── Step 4: the account can log in immediately (no email verification needed) ─
echo ""
echo "--- Step 4: login with the new phone account ---"
LOGIN_STATUS=$(curl -s -o /tmp/fams_reg_login.json -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$PHONE\",\"password\":\"$PASSWORD\"}")
if [ "$LOGIN_STATUS" -eq 200 ] && grep -q '"accessToken"' /tmp/fams_reg_login.json; then
    pass "Login with newly-registered phone account succeeded"
else
    fail "Login after phone registration — expected HTTP 200 with accessToken, got HTTP $LOGIN_STATUS: $(cat /tmp/fams_reg_login.json)"
fi

# ── Step 5: re-registering the same (now verified) phone is rejected ─────────
echo ""
echo "--- Step 5: duplicate phone registration → 409 ---"
DUP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register/send-otp" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$PHONE\"}")
if [ "$DUP_STATUS" -eq 409 ]; then
    pass "send-otp for an already-verified phone rejected (HTTP 409)"
else
    fail "send-otp for verified phone — expected HTTP 409, got HTTP $DUP_STATUS"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""
if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
