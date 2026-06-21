#!/usr/bin/env bash
# Manual forgot/reset password flow test
# Usage: BASE_URL=http://localhost:8080 bash test_forgot_reset_password_manual.sh
#
# Steps:
#   1. Enter your email address (must be a registered + verified account)
#   2. Enter the new password you want to set
#   3. Script triggers a forgot-password request and sends you the reset link
#   4. Script pauses — enter the full reset URL from your email to continue
#   5. Script resets the password, then verifies you can login with the new password

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "=== Manual Forgot / Reset Password Test ==="
echo "Target: $BASE_URL"
echo ""

read -p "Enter the email address of the account to reset: " USER_EMAIL
if [ -z "$USER_EMAIL" ]; then
    echo "ERROR: Email cannot be empty."
    exit 1
fi

read -p "Enter the new password to set (min 8 chars, upper+lower+digit): " NEW_PASSWORD
if [ -z "$NEW_PASSWORD" ]; then
    echo "ERROR: New password cannot be empty."
    exit 1
fi

# Step 1: Request password reset
echo ""
echo "--- Step 1: Requesting password reset for $USER_EMAIL ---"
response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/forgot-password" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$USER_EMAIL\"}")
body=$(echo "$response" | head -n -1)
status=$(echo "$response" | tail -n 1)

if [ "$status" -ne 200 ]; then
    echo "FAIL: forgot-password returned HTTP $status"
    echo "Body: $body"
    exit 1
fi
echo "PASS: Request accepted (HTTP 200). Check your inbox at $USER_EMAIL."

echo ""
echo "======================================================"
echo "  ACTION REQUIRED: Check your inbox at $USER_EMAIL"
echo "  Copy the full reset URL from the email."
echo "  Paste it below when prompted."
echo "======================================================"
echo ""
read -p "Paste the full reset URL here: " RESET_URL

if [ -z "$RESET_URL" ]; then
    echo "ERROR: Reset URL cannot be empty."
    exit 1
fi

# Extract the token from the URL
TOKEN=$(echo "$RESET_URL" | grep -oP '(?<=token=)[^&]+' 2>/dev/null || echo "")
if [ -z "$TOKEN" ]; then
    # Fallback: ask user to enter just the token
    echo "Could not extract token from URL. Please enter just the token value:"
    read -p "Token: " TOKEN
fi

if [ -z "$TOKEN" ]; then
    echo "ERROR: Token cannot be empty."
    exit 1
fi

echo ""
echo "--- Step 2: Resetting password ---"
reset_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/reset-password" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$TOKEN\",\"newPassword\":\"$NEW_PASSWORD\"}")
reset_body=$(echo "$reset_response" | head -n -1)
reset_status=$(echo "$reset_response" | tail -n 1)

if [ "$reset_status" -ne 200 ]; then
    echo "FAIL: reset-password returned HTTP $reset_status"
    echo "Body: $reset_body"
    exit 1
fi
echo "PASS: Password reset successful (HTTP 200)."

# Step 3: Verify login works with new password
echo ""
echo "--- Step 3: Logging in with new password ---"
login_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$USER_EMAIL\",\"password\":\"$NEW_PASSWORD\"}")
login_body=$(echo "$login_response" | head -n -1)
login_status=$(echo "$login_response" | tail -n 1)

if [ "$login_status" -eq 200 ]; then
    access_token=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1)
    if [ -n "$access_token" ]; then
        echo "PASS: Login successful with new password (HTTP 200, token present)."
        echo ""
        echo "Access token: $access_token"
    else
        echo "FAIL: HTTP 200 but no access token in response."
        echo "Body: $login_body"
        exit 1
    fi
else
    echo "FAIL: Login with new password failed (HTTP $login_status)."
    echo "Body: $login_body"
    exit 1
fi

# Step 4: Confirm the reset token is single-use (replay should fail)
echo ""
echo "--- Step 4: Confirming reset token cannot be reused ---"
replay_status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/reset-password" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$TOKEN\",\"newPassword\":\"AnotherPass1\"}")

if [ "$replay_status" -eq 400 ]; then
    echo "PASS: Replay correctly rejected (HTTP 400)."
else
    echo "FAIL: Replay should have returned 400, got HTTP $replay_status."
    exit 1
fi

echo ""
echo "=== Manual test PASSED ==="
