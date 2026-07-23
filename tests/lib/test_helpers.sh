#!/usr/bin/env bash
# Shared helpers for auth test scripts.
#
# register_verified_test_user_token: registers a throwaway user and returns a valid
# access token, without ever touching email/SMS delivery.
#
# Background: before Issue #1 (docs/issues/ISSUES.md — phone registration must verify
# OTP), many test scripts used *phone-only* registration purely as a shortcut to get an
# instantly-authenticated "regular user" token, since POST /auth/register returned tokens
# immediately for phone accounts and /auth/login only accepts email (there is no
# phone+password login). Issue #1 closed that shortcut by requiring a verified Firebase ID
# token for phone registration — correct for production, but it broke this test
# convenience across ~19 scripts.
#
# The fix here doesn't touch any production verification code. It registers a normal
# EMAIL account (still requires email verification before login), then flips
# `email_verified=true` directly in Postgres — the same "bypass the side-effect that isn't
# what this particular test is about" pattern already used for the duplicate-phone seed
# in tests/auth/test_register.sh — then logs in for a real token via the unmodified,
# real /auth/login endpoint.
#
# Usage:
#   source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/test_helpers.sh"
#   TOKEN=$(register_verified_test_user_token "$BASE_URL" "Some Display Name")
#   TOKEN=$(register_verified_test_user_token "$BASE_URL" "Some Display Name" "custom_${TS}@fams.com")

register_verified_test_user_token() {
    local base_url="$1"
    local display_name="${2:-Test User}"
    local email="${3:-testuser_$(date +%s)_$$@fams.com}"
    local password="${4:-TestPass1}"

    curl -s -o /dev/null -X POST "$base_url/api/v1/auth/register" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$email\",\"password\":\"$password\",\"displayName\":\"$display_name\"}"

    docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
        "UPDATE users SET email_verified = true WHERE email = '$email';" > /dev/null

    local login_body
    login_body=$(curl -s -X POST "$base_url/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$email\",\"password\":\"$password\"}")

    echo "$login_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4
}
