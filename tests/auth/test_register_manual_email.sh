#!/usr/bin/env bash
# Manual email verification flow test
# Usage: BASE_URL=http://localhost:8080 bash test_register_manual_email.sh
#
# Steps:
#   1. Enter your real email address
#   2. Script registers a new account and triggers a verification email
#   3. You go to your mailbox, click the verification link
#   4. Press Enter to continue — script then tests that login works

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TS=$(date +%s)

echo "=== Manual Email Verification Test ==="
echo "Target: $BASE_URL"
echo ""

# Prompt for email
read -p "Enter your real email address: " USER_EMAIL
if [ -z "$USER_EMAIL" ]; then
    echo "ERROR: Email cannot be empty."
    exit 1
fi

TEST_PASS="TestPass1"
TEST_NAME="Manual Test User"

# Step 1: Register
echo ""
echo "--- Step 1: Registering account for $USER_EMAIL ---"
response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$USER_EMAIL\",\"password\":\"$TEST_PASS\",\"displayName\":\"$TEST_NAME\"}")
body=$(echo "$response" | head -n -1)
status=$(echo "$response" | tail -n 1)

if [ "$status" -ne 201 ]; then
    echo "FAIL: Registration failed (HTTP $status)"
    echo "Body: $body"
    exit 1
fi

email_verification_required=$(echo "$body" | grep -o '"emailVerificationRequired":true' || true)
if [ -z "$email_verification_required" ]; then
    echo "FAIL: Expected emailVerificationRequired=true but got:"
    echo "Body: $body"
    exit 1
fi

echo "PASS: Registered successfully. Verification email sent to $USER_EMAIL."
echo ""

# Step 2: Confirm login is blocked before verification
echo "--- Step 2: Confirming login is blocked before verification ---"
pre_verify_status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$USER_EMAIL\",\"password\":\"$TEST_PASS\"}")

if [ "$pre_verify_status" -eq 403 ]; then
    echo "PASS: Login correctly blocked before verification (HTTP 403)."
else
    echo "FAIL: Expected HTTP 403 before verification, got HTTP $pre_verify_status."
    exit 1
fi

echo ""
echo "======================================================"
echo "  ACTION REQUIRED: Check your inbox at $USER_EMAIL"
echo "  Click the verification link in the email."
echo "  Once done, press Enter to continue..."
echo "======================================================"
read -r _dummy

# Step 3: Login after verification
echo ""
echo "--- Step 3: Logging in after verification ---"
login_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$USER_EMAIL\",\"password\":\"$TEST_PASS\"}")
login_body=$(echo "$login_response" | head -n -1)
login_status=$(echo "$login_response" | tail -n 1)

if [ "$login_status" -eq 200 ]; then
    access_token=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1)
    refresh_token=$(echo "$login_body" | grep -o '"refreshToken":"[^"]*"' | head -1)
    if [ -n "$access_token" ] && [ -n "$refresh_token" ]; then
        echo "PASS: Login successful after verification (HTTP 200, tokens present)."
        echo ""
        echo "Access token:  ${access_token}"
        echo "Refresh token: ${refresh_token}"
    else
        echo "FAIL: HTTP 200 but tokens missing in response."
        echo "Body: $login_body"
        exit 1
    fi
else
    echo "FAIL: Login after verification failed (HTTP $login_status)."
    echo "Body: $login_body"
    exit 1
fi

echo ""
echo "=== Manual test PASSED ==="
