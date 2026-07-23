#!/usr/bin/env bash
# Manual TOTP 2FA setup test
# Usage: BASE_URL=http://localhost:8080 bash test_totp_manual.sh
#
# Steps:
#   1. Script registers a temporary phone-only account and logs in
#   2. Calls /totp/setup and prints the QR code URL
#   3. Open the URL in your browser and scan the QR code with your Authenticator app
#   4. Enter the 6-digit code from your app to confirm setup
#   5. Script verifies the code was accepted and TOTP is now enabled

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
TS=$(date +%s)

echo "=== Manual TOTP 2FA Setup Test ==="
echo "Target: $BASE_URL"
echo ""

# Step 1: Register a temporary test account
echo "--- Step 1: Creating temporary test account ---"

ACCESS_TOKEN=$(register_verified_test_user_token "$BASE_URL" "TOTP Manual Tester")
if [ -z "$ACCESS_TOKEN" ]; then
    echo "FAIL: Could not extract access token from registration response"
    exit 1
fi
echo "PASS: Account created. Access token obtained."

# Step 2: Initiate TOTP setup
echo ""
echo "--- Step 2: Initiating TOTP setup ---"
setup_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/totp/setup" \
    -H "Authorization: Bearer $ACCESS_TOKEN")
setup_body=$(echo "$setup_response" | head -n -1)
setup_status=$(echo "$setup_response" | tail -n 1)

if [ "$setup_status" -ne 200 ]; then
    echo "FAIL: TOTP setup failed (HTTP $setup_status)"
    echo "Body: $setup_body"
    exit 1
fi

SETUP_TOKEN=$(echo "$setup_body" | grep -o '"setupToken":"[^"]*"' | head -1 | cut -d'"' -f4)
QR_URL=$(echo "$setup_body" | grep -o '"qrCodeUrl":"[^"]*"' | head -1 | cut -d'"' -f4)
MANUAL_KEY=$(echo "$setup_body" | grep -o '"manualEntryKey":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$SETUP_TOKEN" ] || [ -z "$QR_URL" ]; then
    echo "FAIL: Missing setupToken or qrCodeUrl in response"
    echo "Body: $setup_body"
    exit 1
fi

echo "PASS: TOTP setup initiated."
echo ""
echo "======================================================"
echo "  ACTION REQUIRED: Open the URL below in your browser"
echo "  and scan the QR code with Google Authenticator,"
echo "  Authy, or any TOTP app."
echo ""
echo "  $QR_URL"
echo ""
echo "  Or manually enter this key into your app:"
echo "  $MANUAL_KEY"
echo ""
echo "  The QR code page expires in 10 minutes."
echo "======================================================"
echo ""

# Step 3: Prompt for 6-digit code
read -p "Enter the 6-digit code from your Authenticator app: " TOTP_CODE

if [ -z "$TOTP_CODE" ]; then
    echo "ERROR: Code cannot be empty."
    exit 1
fi

# Step 4: Verify TOTP code and enable 2FA
echo ""
echo "--- Step 3: Verifying TOTP code and enabling 2FA ---"
verify_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/totp/verify" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"setupToken\":\"$SETUP_TOKEN\",\"code\":\"$TOTP_CODE\"}")
verify_body=$(echo "$verify_response" | head -n -1)
verify_status=$(echo "$verify_response" | tail -n 1)

if [ "$verify_status" -eq 200 ]; then
    echo "PASS: TOTP 2FA enabled successfully (HTTP 200)."
    echo "Message: $(echo "$verify_body" | grep -o '"message":"[^"]*"' | head -1 | cut -d'"' -f4)"
else
    echo "FAIL: TOTP verify failed (HTTP $verify_status)"
    echo "Body: $verify_body"
    exit 1
fi

# Step 5: Confirm setup token is consumed (replay should fail)
echo ""
echo "--- Step 4: Confirming setup token is single-use ---"
replay_status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/totp/verify" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"setupToken\":\"$SETUP_TOKEN\",\"code\":\"$TOTP_CODE\"}")

if [ "$replay_status" -eq 400 ]; then
    echo "PASS: Replay correctly rejected (HTTP 400)."
else
    echo "FAIL: Replay should return 400, got HTTP $replay_status."
    exit 1
fi

echo ""
echo "=== Manual TOTP test PASSED ==="
echo "TOTP 2FA is now enabled for the test account"
