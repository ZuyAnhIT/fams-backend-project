#!/usr/bin/env bash
# Manual TOTP login test (task 13)
# Usage: BASE_URL=http://localhost:8080 bash test_login_totp_manual.sh
#
# Steps:
#   1. Enter your real email address + a password
#   2. Script registers the account and sends a verification email
#   3. Click the verification link → press Enter to continue
#   4. Script logs in and initiates TOTP setup — opens a QR code URL for you to scan
#   5. Enter the 6-digit code to confirm TOTP is configured
#   6. Script logs out, then attempts login again → TOTP required
#   7. Enter the 6-digit code from your Authenticator app to complete login

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TS=$(date +%s)

echo "=== Manual TOTP Login Test ==="
echo "Target: $BASE_URL"
echo ""

# Step 1: Collect credentials
read -p "Enter your real email address: " USER_EMAIL
if [ -z "$USER_EMAIL" ]; then echo "ERROR: Email cannot be empty."; exit 1; fi

read -p "Enter a password (min 8 chars, upper+lower+digit): " USER_PASS
if [ -z "$USER_PASS" ]; then echo "ERROR: Password cannot be empty."; exit 1; fi

# Step 2: Register account
echo ""
echo "--- Step 1: Registering account for $USER_EMAIL ---"
reg_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$USER_EMAIL\",\"password\":\"$USER_PASS\",\"displayName\":\"TOTP Login Tester\"}")
reg_body=$(echo "$reg_response" | head -n -1)
reg_status=$(echo "$reg_response" | tail -n 1)

if [ "$reg_status" -ne 201 ]; then
    echo "FAIL: Registration failed (HTTP $reg_status): $reg_body"; exit 1
fi

email_ver_required=$(echo "$reg_body" | grep -o '"emailVerificationRequired":true' || true)
if [ -z "$email_ver_required" ]; then
    echo "FAIL: Expected emailVerificationRequired=true"; exit 1
fi
echo "PASS: Registered. Verification email sent to $USER_EMAIL."

# Step 3: Pause for email verification
echo ""
echo "======================================================"
echo "  ACTION REQUIRED: Check your inbox at $USER_EMAIL"
echo "  Click the verification link in the email."
echo "  Then press Enter to continue..."
echo "======================================================"
read -r _dummy

# Step 4: Login to get access token
echo ""
echo "--- Step 2: Logging in to get access token ---"
login_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$USER_EMAIL\",\"password\":\"$USER_PASS\"}")
login_body=$(echo "$login_response" | head -n -1)
login_status=$(echo "$login_response" | tail -n 1)

if [ "$login_status" -ne 200 ]; then
    echo "FAIL: Login failed (HTTP $login_status): $login_body"; exit 1
fi
ACCESS_TOKEN=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$ACCESS_TOKEN" ]; then
    echo "FAIL: No access token in login response"; exit 1
fi
echo "PASS: Login successful. Access token obtained."

# Step 5: Initiate TOTP setup
echo ""
echo "--- Step 3: Setting up TOTP ---"
setup_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/totp/setup" \
    -H "Authorization: Bearer $ACCESS_TOKEN")
setup_body=$(echo "$setup_response" | head -n -1)
setup_status=$(echo "$setup_response" | tail -n 1)

if [ "$setup_status" -ne 200 ]; then
    echo "FAIL: TOTP setup failed (HTTP $setup_status): $setup_body"; exit 1
fi
SETUP_TOKEN=$(echo "$setup_body" | grep -o '"setupToken":"[^"]*"' | head -1 | cut -d'"' -f4)
QR_URL=$(echo "$setup_body" | grep -o '"qrCodeUrl":"[^"]*"' | head -1 | cut -d'"' -f4)
MANUAL_KEY=$(echo "$setup_body" | grep -o '"manualEntryKey":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$SETUP_TOKEN" ] || [ -z "$QR_URL" ]; then
    echo "FAIL: Missing fields in TOTP setup response"; exit 1
fi

echo "PASS: TOTP setup initiated."
echo ""
echo "======================================================"
echo "  ACTION REQUIRED: Open the URL below in your browser"
echo "  and scan the QR code with your Authenticator app."
echo ""
echo "  $QR_URL"
echo ""
echo "  Or manually enter: $MANUAL_KEY"
echo "======================================================"
echo ""

# Step 6: Enter 6-digit code to confirm TOTP setup
read -p "Enter the 6-digit code from your Authenticator app to confirm setup: " SETUP_CODE
if [ -z "$SETUP_CODE" ]; then echo "ERROR: Code cannot be empty."; exit 1; fi

verify_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/totp/verify" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"setupToken\":\"$SETUP_TOKEN\",\"code\":\"$SETUP_CODE\"}")
verify_body=$(echo "$verify_response" | head -n -1)
verify_status=$(echo "$verify_response" | tail -n 1)

if [ "$verify_status" -ne 200 ]; then
    echo "FAIL: TOTP enable failed (HTTP $verify_status): $verify_body"; exit 1
fi
echo "PASS: TOTP two-factor authentication is now enabled."

# Step 7: Login again — should trigger TOTP challenge
echo ""
echo "--- Step 4: Logging in again (TOTP should now be required) ---"
login2_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$USER_EMAIL\",\"password\":\"$USER_PASS\"}")
login2_body=$(echo "$login2_response" | head -n -1)
login2_status=$(echo "$login2_response" | tail -n 1)

if [ "$login2_status" -ne 200 ]; then
    echo "FAIL: Second login failed (HTTP $login2_status): $login2_body"; exit 1
fi

totp_required=$(echo "$login2_body" | grep -o '"totpRequired":true' || true)
PENDING_TOKEN=$(echo "$login2_body" | grep -o '"pendingToken":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$totp_required" ] || [ -z "$PENDING_TOKEN" ]; then
    echo "FAIL: Expected totpRequired=true + pendingToken, got: $login2_body"; exit 1
fi
echo "PASS: Login returned totpRequired=true. Pending token received."
echo ""

# Step 8: Complete login with TOTP code
read -p "Enter the 6-digit code from your Authenticator app to complete login: " LOGIN_CODE
if [ -z "$LOGIN_CODE" ]; then echo "ERROR: Code cannot be empty."; exit 1; fi

echo ""
echo "--- Step 5: Completing login with TOTP code ---"
final_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login/totp" \
    -H "Content-Type: application/json" \
    -d "{\"pendingToken\":\"$PENDING_TOKEN\",\"code\":\"$LOGIN_CODE\"}")
final_body=$(echo "$final_response" | head -n -1)
final_status=$(echo "$final_response" | tail -n 1)

if [ "$final_status" -eq 200 ]; then
    final_access=$(echo "$final_body" | grep -o '"accessToken":"[^"]*"' | head -1)
    final_refresh=$(echo "$final_body" | grep -o '"refreshToken":"[^"]*"' | head -1)
    if [ -n "$final_access" ] && [ -n "$final_refresh" ]; then
        echo "PASS: TOTP login successful (HTTP 200, tokens present)."
        echo ""
        echo "Access token:  $final_access"
        echo "Refresh token: $final_refresh"
    else
        echo "FAIL: HTTP 200 but tokens missing: $final_body"; exit 1
    fi
else
    echo "FAIL: TOTP login failed (HTTP $final_status): $final_body"; exit 1
fi

# Step 9: Verify pending token is single-use
echo ""
echo "--- Step 6: Confirming pending token is single-use ---"
replay_status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login/totp" \
    -H "Content-Type: application/json" \
    -d "{\"pendingToken\":\"$PENDING_TOKEN\",\"code\":\"$LOGIN_CODE\"}")
if [ "$replay_status" -eq 401 ]; then
    echo "PASS: Replay correctly rejected (HTTP 401)."
else
    echo "FAIL: Replay should return 401, got HTTP $replay_status."
    exit 1
fi

echo ""
echo "=== Manual TOTP login test PASSED ==="
