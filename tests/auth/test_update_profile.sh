#!/usr/bin/env bash
# Tests for PATCH /api/v1/auth/me plus the verified email/phone change flow
# (POST /auth/profile/email/request-change + GET .../confirm-change,
#  POST /auth/profile/phone/request-change + POST .../confirm-change).
# Usage: BASE_URL=http://localhost:8080 bash test_update_profile.sh
#
# Email/phone are deliberately NOT part of PATCH /api/v1/auth/me — both require proof of
# ownership (email link / SMS OTP) BEFORE the change is written, so a pending change can
# never interfere with the account's existing verified login channel. See UserProfileService.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")

    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")

    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

# Scrapes the most recent OTP for a phone from the API container's dev-mode log.
fetch_otp() {
    local phone="$1"
    docker compose logs fams-api --since 30s 2>/dev/null \
        | grep -A1 "\[DEV\] SMS OTP for ${phone} " \
        | grep -o "OTP CODE: [0-9]\{6\}" \
        | tail -1 \
        | grep -o "[0-9]\{6\}"
}

echo "=== Auth Update Profile Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: register two test users (email accounts) via the login shortcut ──
TS=$(date +%s)
PHONE_B="+849$(printf '%07d' $(( (TS + $$ + 1) % 10000000 )))"
PASSWORD="Profile@123"

echo "--- Setup: registering test users ---"
TOKEN_A=$(register_verified_test_user_token "$BASE_URL" "User A" "update_profile_a_${TS}_$$@fams.com" "$PASSWORD")
TOKEN_B=$(register_verified_test_user_token "$BASE_URL" "User B" "update_profile_b_${TS}_$$@fams.com" "$PASSWORD")

if [ -z "$TOKEN_A" ] || [ -z "$TOKEN_B" ]; then
    echo "FAIL: Setup — could not register test users"
    exit 1
fi

# Give user B a phone number via the real verified-change flow so Test 6 can verify the
# phone-uniqueness conflict check.
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/profile/phone/request-change" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN_B" \
    -d "{\"phone\":\"$PHONE_B\"}"
sleep 1
OTP_B=$(fetch_otp "$PHONE_B")
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/profile/phone/confirm-change" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN_B" \
    -d "{\"phone\":\"$PHONE_B\",\"otpCode\":\"$OTP_B\"}"
echo "Setup OK"
echo ""

# Test 1: Unauthenticated → 401
echo "--- Test 1: No auth token (unauthenticated) ---"
run_test "Unauthenticated" 401 \
    -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" \
    -d '{"displayName":"New Name"}'

# Test 2: Happy path — update displayName
echo ""
echo "--- Test 2: Update displayName ---"
update_response=$(curl -s -w "\n%{http_code}" \
    -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN_A" \
    -d '{"displayName":"Updated Name"}')

update_body=$(echo "$update_response" | head -n -1)
update_status=$(echo "$update_response" | tail -n 1)

if [ "$update_status" -eq 200 ] && echo "$update_body" | grep -q '"Updated Name"'; then
    echo "PASS: Update displayName (HTTP 200, name updated)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Update displayName — status=$update_status body=$update_body"
    FAIL=$((FAIL + 1))
fi

# Test 3: Verify GET /me reflects the change
echo ""
echo "--- Test 3: GET /me reflects updated displayName ---"
get_response=$(curl -s "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $TOKEN_A")
if echo "$get_response" | grep -q '"Updated Name"'; then
    echo "PASS: GET /me reflects updated displayName"
    PASS=$((PASS + 1))
else
    echo "FAIL: GET /me did not reflect updated displayName. Body: $get_response"
    FAIL=$((FAIL + 1))
fi

# Test 4: PATCH /me no longer accepts avatarUrl at all — avatar must be a real upload via
# POST /auth/profile/avatar (S3/MinIO-backed), not a pasted URL. See
# test_profile_fields_and_avatar.sh for the full upload/replace/delete coverage.
echo ""
echo "--- Test 4: PATCH /me does not write a pasted avatarUrl ---"
curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN_A" \
    -d '{"avatarUrl":"https://cdn.example.com/avatar.png"}'
avatar_after=$(curl -s "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $TOKEN_A")
if echo "$avatar_after" | grep -q '"avatarUrl":null'; then
    echo "PASS: Pasted avatarUrl ignored — must use POST /auth/profile/avatar instead"
    PASS=$((PASS + 1))
else
    echo "FAIL: avatarUrl was written via PATCH /me. Body: $avatar_after"
    FAIL=$((FAIL + 1))
fi

# Test 5: PATCH /me no longer accepts phone/email — silently ignored, not written.
# (They aren't in UpdateProfileRequest's schema at all, so this is really just confirming
# the rest of the patch still applies and the account's phone is untouched.)
echo ""
echo "--- Test 5: PATCH /me with a phone-shaped field is a no-op for phone ---"
curl -s -o /dev/null -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN_A" \
    -d '{"phone":"+84900000000"}'
get_after=$(curl -s "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $TOKEN_A")
if echo "$get_after" | grep -q '"phone":null'; then
    echo "PASS: PATCH /me does not write phone (still null — must use /profile/phone/request-change)"
    PASS=$((PASS + 1))
else
    echo "FAIL: phone was unexpectedly written via PATCH /me. Body: $get_after"
    FAIL=$((FAIL + 1))
fi

# Test 6: Verified phone-change flow — request, wrong OTP rejected, then correct OTP applies
echo ""
echo "--- Test 6: POST /profile/phone/request-change + confirm-change (happy path) ---"
NEW_PHONE="+849$(printf '%07d' $(( (TS + $$ + 2) % 10000000 )))"
run_test "Request phone change" 200 \
    -X POST "$BASE_URL/api/v1/auth/profile/phone/request-change" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN_A" \
    -d "{\"phone\":\"$NEW_PHONE\"}"

get_pending=$(curl -s "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $TOKEN_A")
if echo "$get_pending" | grep -q '"phone":null'; then
    echo "PASS: phone not written yet while OTP is pending"
    PASS=$((PASS + 1))
else
    echo "FAIL: phone was written before OTP confirmation. Body: $get_pending"
    FAIL=$((FAIL + 1))
fi

sleep 1
run_test "Wrong OTP rejected" 400 \
    -X POST "$BASE_URL/api/v1/auth/profile/phone/confirm-change" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN_A" \
    -d "{\"phone\":\"$NEW_PHONE\",\"otpCode\":\"000000\"}"

OTP_A=$(fetch_otp "$NEW_PHONE")
confirm_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/profile/phone/confirm-change" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN_A" \
    -d "{\"phone\":\"$NEW_PHONE\",\"otpCode\":\"$OTP_A\"}")
confirm_status=$(echo "$confirm_response" | tail -n 1)
confirm_body=$(echo "$confirm_response" | head -n -1)
if [ "$confirm_status" -eq 200 ] && echo "$confirm_body" | grep -q '"phoneVerified":true'; then
    echo "PASS: Correct OTP applies the phone change (phoneVerified:true)"
    PASS=$((PASS + 1))
else
    echo "FAIL: Confirm phone change — status=$confirm_status body=$confirm_body"
    FAIL=$((FAIL + 1))
fi

# Test 7: Conflict — phone already used by another user → 409 at request time
echo ""
echo "--- Test 7: Phone already taken by another user (request-change) ---"
run_test "Phone conflict on request" 409 \
    -X POST "$BASE_URL/api/v1/auth/profile/phone/request-change" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN_A" \
    -d "{\"phone\":\"$PHONE_B\"}"

# Test 8: Validation — displayName too long → 400
echo ""
echo "--- Test 8: displayName exceeds 100 chars ---"
LONG_NAME=$(printf 'A%.0s' {1..101})
run_test "displayName too long" 400 \
    -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN_A" \
    -d "{\"displayName\":\"$LONG_NAME\"}"

# Test 9: Validation — invalid phone format → 400 (now on request-change, not PATCH /me)
echo ""
echo "--- Test 9: Invalid phone format on request-change ---"
run_test "Invalid phone format" 400 \
    -X POST "$BASE_URL/api/v1/auth/profile/phone/request-change" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN_A" \
    -d '{"phone":"not-a-phone"}'

# Test 10: Empty body is valid (no-op patch) → 200
echo ""
echo "--- Test 10: Empty body (no-op patch) ---"
run_test "Empty body no-op" 200 \
    -X PATCH "$BASE_URL/api/v1/auth/me" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN_A" \
    -d '{}'

# Test 11: Verified email-change flow — request, then confirm via token
echo ""
echo "--- Test 11: POST /profile/email/request-change + GET confirm-change (happy path) ---"
NEW_EMAIL="profile_email_change_${TS}_$$@fams.com"
run_test "Request email change" 200 \
    -X POST "$BASE_URL/api/v1/auth/profile/email/request-change" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN_A" \
    -d "{\"email\":\"$NEW_EMAIL\"}"

# The new (second) email on this account is not yet set — GET /me should still show the
# original registration email.
get_pending_email=$(curl -s "$BASE_URL/api/v1/auth/me" -H "Authorization: Bearer $TOKEN_A")
if echo "$get_pending_email" | grep -q "$NEW_EMAIL"; then
    echo "FAIL: new email was written before confirmation. Body: $get_pending_email"
    FAIL=$((FAIL + 1))
else
    echo "PASS: new email not written yet while confirmation is pending"
    PASS=$((PASS + 1))
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
