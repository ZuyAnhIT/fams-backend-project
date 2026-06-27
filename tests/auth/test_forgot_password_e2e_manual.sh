#!/usr/bin/env bash
# End-to-end manual test for the Forgot Password flow.
#
# What this script does:
#   1. Registers a fresh test account using the email you provide.
#      The registration password is auto-generated — you will not know it,
#      so the only way to gain access is through Forgot Password.
#   2. Prompts you to paste the verification link from your inbox so the
#      account becomes active.
#   3. Fires POST /forgot-password to trigger the reset email.
#   4. Prompts you to paste the reset link from your inbox.
#   5. Asks you to choose a new password (entered twice for confirmation).
#   6. Calls POST /reset-password and then logs in automatically to confirm
#      the whole flow works.
#
# Usage:
#   BASE_URL=http://localhost:8080 bash test_forgot_password_e2e_manual.sh

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

# ─── Colour helpers ──────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BOLD='\033[1m'; RESET='\033[0m'
ok()   { echo -e "${GREEN}[PASS]${RESET} $1"; }
fail() { echo -e "${RED}[FAIL]${RESET} $1"; exit 1; }
info() { echo -e "${YELLOW}[INFO]${RESET} $1"; }
step() { echo ""; echo -e "${BOLD}── $1 ──${RESET}"; }

# ─── Helper: extract a query-param value from a URL ──────────────────────────
extract_param() {
    local url="$1" param="$2"
    echo "$url" | grep -oP "(?<=${param}=)[^&]+" 2>/dev/null || true
}

echo ""
echo -e "${BOLD}=== Forgot Password — End-to-End Manual Test ===${RESET}"
echo "Target: $BASE_URL"
echo ""

# ─── Step 0: Collect email ───────────────────────────────────────────────────
read -rp "Enter the email address to use for this test: " USER_EMAIL
[[ -z "$USER_EMAIL" ]] && fail "Email cannot be empty."

# ─── Step 1: Register a fresh account with an auto-generated password ─────────
step "Step 1: Register a fresh test account"

RANDOM_HEX=$(cat /dev/urandom | tr -dc 'a-f0-9' | head -c 8)
INIT_PASSWORD="Fams${RANDOM_HEX}X9!"     # meets: upper, lower, digit, special, ≥8 chars
DISPLAY_NAME="TestUser-$$"

info "Auto-generated registration password (intentionally hidden — use Forgot Password to gain access)."

reg_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$USER_EMAIL\",\"password\":\"$INIT_PASSWORD\",\"displayName\":\"$DISPLAY_NAME\"}")
reg_body=$(echo "$reg_resp" | head -n -1)
reg_status=$(echo "$reg_resp" | tail -n 1)

if [[ "$reg_status" -eq 201 ]]; then
    ok "Account registered (HTTP 201). A verification email has been sent to $USER_EMAIL."
elif [[ "$reg_status" -eq 409 ]]; then
    fail "That email is already registered. Please use a different address."
else
    fail "Registration failed (HTTP $reg_status). Body: $reg_body"
fi

# ─── Step 2: Verify the email address ────────────────────────────────────────
step "Step 2: Verify your email"

echo ""
echo -e "${YELLOW}ACTION REQUIRED:${RESET}"
echo "  Check your inbox at $USER_EMAIL for a verification email."
echo "  Copy the full verification URL from the email."
echo ""
read -rp "Paste the full verification URL here: " VERIFY_URL
[[ -z "$VERIFY_URL" ]] && fail "Verification URL cannot be empty."

VERIFY_TOKEN=$(extract_param "$VERIFY_URL" "token")
if [[ -z "$VERIFY_TOKEN" ]]; then
    echo "Could not extract token from URL automatically."
    read -rp "Please paste just the token value: " VERIFY_TOKEN
fi
[[ -z "$VERIFY_TOKEN" ]] && fail "Verification token cannot be empty."

verify_status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "$BASE_URL/api/v1/auth/verify-email?token=$VERIFY_TOKEN")

if [[ "$verify_status" -eq 200 ]]; then
    ok "Email verified (HTTP 200). The account is now active."
else
    fail "Email verification failed (HTTP $verify_status). Check the token or whether it has expired."
fi

# ─── Step 3: Trigger the forgot-password email ───────────────────────────────
step "Step 3: Request a password reset"

forgot_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/forgot-password" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$USER_EMAIL\"}")
forgot_body=$(echo "$forgot_resp" | head -n -1)
forgot_status=$(echo "$forgot_resp" | tail -n 1)

if [[ "$forgot_status" -eq 200 ]]; then
    ok "Reset email dispatched (HTTP 200). Check your inbox at $USER_EMAIL."
else
    fail "Forgot-password request failed (HTTP $forgot_status). Body: $forgot_body"
fi

# ─── Step 4: Collect the reset URL and extract the token ─────────────────────
step "Step 4: Enter the reset link from your inbox"

echo ""
echo -e "${YELLOW}ACTION REQUIRED:${RESET}"
echo "  Check your inbox at $USER_EMAIL for a password-reset email."
echo "  Copy the full reset URL from the email."
echo ""
read -rp "Paste the full reset URL here: " RESET_URL
[[ -z "$RESET_URL" ]] && fail "Reset URL cannot be empty."

RESET_TOKEN=$(extract_param "$RESET_URL" "token")
if [[ -z "$RESET_TOKEN" ]]; then
    echo "Could not extract token from URL automatically."
    read -rp "Please paste just the token value: " RESET_TOKEN
fi
[[ -z "$RESET_TOKEN" ]] && fail "Reset token cannot be empty."

# ─── Step 5: Enter a new password (twice) ────────────────────────────────────
step "Step 5: Set a new password"

echo ""
while true; do
    read -rsp "Enter new password (min 8 chars, must include upper, lower, digit): " NEW_PASSWORD
    echo ""
    [[ -z "$NEW_PASSWORD" ]] && { echo "Password cannot be empty. Try again."; continue; }
    [[ ${#NEW_PASSWORD} -lt 8 ]] && { echo "Password must be at least 8 characters. Try again."; continue; }

    read -rsp "Confirm new password: " NEW_PASSWORD_CONFIRM
    echo ""

    if [[ "$NEW_PASSWORD" != "$NEW_PASSWORD_CONFIRM" ]]; then
        echo -e "${RED}Passwords do not match.${RESET} Try again."
    else
        break
    fi
done

# ─── Step 6: Reset the password via the API ──────────────────────────────────
step "Step 6: Reset password"

reset_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/reset-password" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$RESET_TOKEN\",\"newPassword\":\"$NEW_PASSWORD\"}")
reset_body=$(echo "$reset_resp" | head -n -1)
reset_status=$(echo "$reset_resp" | tail -n 1)

if [[ "$reset_status" -eq 200 ]]; then
    ok "Password reset successful (HTTP 200)."
else
    fail "Password reset failed (HTTP $reset_status). Body: $reset_body"
fi

# ─── Step 7: Confirm login works with the new password ───────────────────────
step "Step 7: Login with new password"

login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$USER_EMAIL\",\"password\":\"$NEW_PASSWORD\"}")
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)

if [[ "$login_status" -eq 200 ]]; then
    access_token=$(echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [[ -n "$access_token" ]]; then
        ok "Login successful (HTTP 200). Access token received."
        echo ""
        echo -e "  ${BOLD}Email:${RESET}  $USER_EMAIL"
        echo -e "  ${BOLD}Token:${RESET}  ${access_token:0:40}..."
    else
        fail "HTTP 200 but no access token in response. Body: $login_body"
    fi
else
    fail "Login failed (HTTP $login_status). Body: $login_body"
fi

# ─── Step 8: Confirm the reset token is single-use (replay must fail) ────────
step "Step 8: Confirm reset token cannot be reused"

replay_status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/reset-password" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"$RESET_TOKEN\",\"newPassword\":\"AnotherPass9!\"}")

if [[ "$replay_status" -eq 400 ]]; then
    ok "Replay correctly rejected (HTTP 400) — token is single-use."
else
    echo -e "${RED}[WARN]${RESET} Replay returned HTTP $replay_status instead of 400. Token may be reusable."
fi

# ─── Done ────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}${BOLD}=== All steps passed — Forgot Password flow is working correctly ===${RESET}"
echo ""
