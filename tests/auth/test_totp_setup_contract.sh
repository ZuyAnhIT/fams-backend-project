#!/usr/bin/env bash
# Tests for the TOTP setup contract update (2026-08-12, FE request: FE stopped iframing
# qrCodeUrl — X-Frame-Options: DENY blocks that by design and stays — and needs otpauthUri
# to render the QR client-side instead).
#
# POST /api/v1/auth/totp/setup   — now also returns otpauthUri + expiresAt; qrCodeUrl kept
#                                   but deprecated; 409 if TOTP already enabled; invalidates
#                                   any prior pending setup for the same user.
# GET  /api/v1/auth/totp/qr      — deprecated but still functional; same headers as setup.
# POST /api/v1/auth/totp/verify  — unchanged behavior, re-verified against the new field.
#
# Usage: BASE_URL=http://localhost:8080 bash test_totp_setup_contract.sh
# Requires: python3 (TOTP code generation), docker (direct Postgres/Redis inspection —
# same pattern already used by tests/lib/test_helpers.sh and test_totp_2fa_hardening.sh).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
TS=$(date +%s)

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

run_test() {
    local name="$1" expected_status="$2"
    local actual_status
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${@:3}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        pass "$name (HTTP $actual_status)"
    else
        fail "$name — expected HTTP $expected_status, got HTTP $actual_status"
    fi
}

# Compute a TOTP code from a base32 secret (same formula used throughout tests/auth/*.sh).
totp_code() {
    python3 - "$1" <<'PYEOF'
import sys, hmac, hashlib, struct, time, base64
secret = sys.argv[1]
pad = (8 - len(secret) % 8) % 8
key = base64.b32decode((secret + '=' * pad).upper())
counter = int(time.time()) // 30
h = hmac.new(key, struct.pack('>Q', counter), hashlib.sha1).digest()
o = h[-1] & 15
code = (struct.unpack('>I', h[o:o+4])[0] & 0x7fffffff) % 1000000
print(f'{code:06d}')
PYEOF
}

# URL-decode the account portion of an otpauth label — used to prove round-trip correctness
# for emails/phones containing +, spaces, or other characters requiring percent-encoding.
decode_account() {
    python3 - "$1" <<'PYEOF'
import sys
from urllib.parse import unquote
uri = sys.argv[1]
label = uri.split("otpauth://totp/")[1].split("?")[0]
print(unquote(label))
PYEOF
}

echo "=== TOTP Setup Contract Tests (2026-08-12) ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: two throwaway accounts ────────────────────────────────────────────
EMAIL1="totp.contract.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMAIL1\",\"password\":\"TestPass1\",\"displayName\":\"Totp Contract\"}"
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "UPDATE users SET email_verified = true WHERE email = '$EMAIL1';" > /dev/null
TOKEN1=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$EMAIL1\",\"password\":\"TestPass1\"}" \
    | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
USER_ID1=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -A -c \
    "SELECT id FROM users WHERE email='$EMAIL1';")

TOKEN2=$(register_verified_test_user_token "$BASE_URL" "Totp Contract 2" "totp.contract2.${TS}@example.com")

if [ -z "$TOKEN1" ] || [ -z "$TOKEN2" ]; then
    echo "SETUP FAILED: could not obtain access tokens"
    exit 1
fi
echo "Two test accounts ready."
echo ""

# ── Test 1: setup response has all 5 fields, otpauthUri well-formed ─────────
echo "--- Test 1: Setup response fields + otpauthUri format ---"
setup1=$(curl -s -D /tmp/totp_contract_headers.txt -X POST "$BASE_URL/api/v1/auth/totp/setup" \
    -H "Authorization: Bearer $TOKEN1")

SETUP_TOKEN1=$(echo "$setup1" | grep -o '"setupToken":"[^"]*"' | head -1 | cut -d'"' -f4)
OTPAUTH1=$(echo "$setup1" | grep -o '"otpauthUri":"[^"]*"' | head -1 | cut -d'"' -f4 | sed 's/\\u0026/\&/g')
MANUAL_KEY1=$(echo "$setup1" | grep -o '"manualEntryKey":"[^"]*"' | head -1 | cut -d'"' -f4)
QR_URL1=$(echo "$setup1" | grep -o '"qrCodeUrl":"[^"]*"' | head -1 | cut -d'"' -f4)
EXPIRES_AT1=$(echo "$setup1" | grep -o '"expiresAt":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -n "$SETUP_TOKEN1" ] && [ -n "$OTPAUTH1" ] && [ -n "$MANUAL_KEY1" ] && [ -n "$QR_URL1" ] && [ -n "$EXPIRES_AT1" ]; then
    pass "Setup response has setupToken + otpauthUri + manualEntryKey + qrCodeUrl (deprecated, kept) + expiresAt"
else
    fail "Setup response missing a required field — body: $setup1"
fi

if [[ "$OTPAUTH1" == otpauth://totp/FAMS:* ]] \
    && [[ "$OTPAUTH1" == *"issuer=FAMS"* ]] \
    && [[ "$OTPAUTH1" == *"algorithm=SHA1"* ]] \
    && [[ "$OTPAUTH1" == *"digits=6"* ]] \
    && [[ "$OTPAUTH1" == *"period=30"* ]]; then
    pass "otpauthUri has issuer=FAMS, algorithm=SHA1, digits=6, period=30"
else
    fail "otpauthUri format wrong: $OTPAUTH1"
fi

# ── Test 2: secret in otpauthUri matches manualEntryKey ──────────────────────
echo ""
echo "--- Test 2: Secret in otpauthUri matches manualEntryKey ---"
URI_SECRET=$(echo "$OTPAUTH1" | grep -o 'secret=[^&]*' | cut -d= -f2)
if [ "$URI_SECRET" = "$MANUAL_KEY1" ]; then
    pass "otpauthUri secret == manualEntryKey"
else
    fail "Mismatch: URI secret='$URI_SECRET' manualEntryKey='$MANUAL_KEY1'"
fi

# ── Test 3: email with special chars round-trips correctly ──────────────────
echo ""
echo "--- Test 3: Email with +, dot, special chars encoded correctly ---"
DECODED_LABEL1=$(decode_account "$OTPAUTH1")
if [ "$DECODED_LABEL1" = "FAMS:$EMAIL1" ]; then
    pass "Decoded otpauthUri label matches 'FAMS:$EMAIL1' exactly"
else
    fail "Decoded label mismatch: got '$DECODED_LABEL1', expected 'FAMS:$EMAIL1'"
fi

# Dedicated +/space case, since $EMAIL1 above has no '+' — use a fresh account for it.
PLUS_EMAIL="totp.plus+space.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$PLUS_EMAIL\",\"password\":\"TestPass1\",\"displayName\":\"Plus Test\"}"
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "UPDATE users SET email_verified = true WHERE email = '$PLUS_EMAIL';" > /dev/null
PLUS_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$PLUS_EMAIL\",\"password\":\"TestPass1\"}" \
    | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
PLUS_SETUP=$(curl -s -X POST "$BASE_URL/api/v1/auth/totp/setup" -H "Authorization: Bearer $PLUS_TOKEN")
PLUS_OTPAUTH=$(echo "$PLUS_SETUP" | grep -o '"otpauthUri":"[^"]*"' | head -1 | cut -d'"' -f4 | sed 's/\\u0026/\&/g')
case "$PLUS_OTPAUTH" in
    *"%2B"*"%40"*) pass "'+' encoded as %2B and '@' as %40 (not left raw / not '+'-for-space form-encoded)" ;;
    *) fail "Special chars not correctly encoded: $PLUS_OTPAUTH" ;;
esac
DECODED_PLUS=$(decode_account "$PLUS_OTPAUTH")
if [ "$DECODED_PLUS" = "FAMS:$PLUS_EMAIL" ]; then
    pass "Decoded '+'-email label round-trips exactly"
else
    fail "Decoded '+'-email label mismatch: got '$DECODED_PLUS'"
fi
PLUS_USER_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -A -c \
    "SELECT id FROM users WHERE email='$PLUS_EMAIL';")

# ── Test 4: phone-only account (no email) still produces a valid URI ────────
echo ""
echo "--- Test 4: Phone-only account (email=NULL) produces valid otpauthUri ---"
PHONE_EMAIL="totp.phonefallback.${TS}@example.com"
curl -s -o /dev/null -X POST "$BASE_URL/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$PHONE_EMAIL\",\"password\":\"TestPass1\",\"displayName\":\"Phone Fallback\"}"
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "UPDATE users SET email_verified = true, phone='+84900111222', phone_verified=true WHERE email = '$PHONE_EMAIL';" > /dev/null
PHONE_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"identifier\":\"$PHONE_EMAIL\",\"password\":\"TestPass1\"}" \
    | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)
PHONE_USER_ID=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -A -c \
    "SELECT id FROM users WHERE email='$PHONE_EMAIL';")
docker exec fams-postgres psql -U fams_user -d fams_db -q -c \
    "UPDATE users SET email = NULL WHERE id = '$PHONE_USER_ID';" > /dev/null

PHONE_SETUP=$(curl -s -X POST "$BASE_URL/api/v1/auth/totp/setup" -H "Authorization: Bearer $PHONE_TOKEN")
PHONE_OTPAUTH=$(echo "$PHONE_SETUP" | grep -o '"otpauthUri":"[^"]*"' | head -1 | cut -d'"' -f4 | sed 's/\\u0026/\&/g')
PHONE_STATUS_CHECK=$(echo "$PHONE_SETUP" | grep -c '"success":true' || true)
if [ "$PHONE_STATUS_CHECK" -ge 1 ] && [ -n "$PHONE_OTPAUTH" ]; then
    DECODED_PHONE=$(decode_account "$PHONE_OTPAUTH")
    if [ "$DECODED_PHONE" = "FAMS:+84900111222" ]; then
        pass "Phone-only account: otpauthUri account falls back to phone, decodes to 'FAMS:+84900111222'"
    else
        fail "Phone fallback decoded wrong: got '$DECODED_PHONE'"
    fi
else
    fail "Phone-only account setup failed — body: $PHONE_SETUP"
fi

# ── Test 5: code generated from otpauthUri secret verifies successfully ─────
echo ""
echo "--- Test 5: Code from otpauthUri secret verifies (happy path) ---"
CODE1=$(totp_code "$MANUAL_KEY1")
verify1_status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/totp/verify" \
    -H "Authorization: Bearer $TOKEN1" -H "Content-Type: application/json" \
    -d "{\"setupToken\":\"$SETUP_TOKEN1\",\"code\":\"$CODE1\"}")
if [ "$verify1_status" -eq 200 ]; then
    pass "Verify with code computed from otpauthUri's secret → HTTP 200, TOTP enabled"
else
    fail "Verify with correct code — expected HTTP 200, got HTTP $verify1_status"
fi

# ── Test 6: setup token deleted from Redis after successful enable ──────────
echo ""
echo "--- Test 6: Setup token removed from Redis after enable ---"
redis_val=$(docker exec fams-redis redis-cli -a redispassword123 --no-auth-warning GET "totp:setup:$SETUP_TOKEN1" 2>/dev/null || true)
if [ -z "$redis_val" ]; then
    pass "Redis key totp:setup:$SETUP_TOKEN1 no longer exists after successful enable"
else
    fail "Redis key still present after enable: $redis_val"
fi

# ── Test 7: used setup token cannot be reused (replay) ───────────────────────
echo ""
echo "--- Test 7: Reusing a consumed setup token fails ---"
CODE1_AGAIN=$(totp_code "$MANUAL_KEY1")
run_test "Replay consumed setupToken" 400 \
    -X POST "$BASE_URL/api/v1/auth/totp/verify" \
    -H "Authorization: Bearer $TOKEN1" -H "Content-Type: application/json" \
    -d "{\"setupToken\":\"$SETUP_TOKEN1\",\"code\":\"$CODE1_AGAIN\"}"

# ── Test 8: already-enabled account calling setup again → 409 ───────────────
echo ""
echo "--- Test 8: Setup on already-enabled account returns 409 ---"
run_test "Setup when already enabled" 409 \
    -X POST "$BASE_URL/api/v1/auth/totp/setup" \
    -H "Authorization: Bearer $TOKEN1"

# ── Test 9: repeated setup calls invalidate the prior pending session ───────
echo ""
echo "--- Test 9: A second setup call invalidates the first pending session ---"
setupA=$(curl -s -X POST "$BASE_URL/api/v1/auth/totp/setup" -H "Authorization: Bearer $TOKEN2")
TOKEN_A=$(echo "$setupA" | grep -o '"setupToken":"[^"]*"' | head -1 | cut -d'"' -f4)
SECRET_A=$(echo "$setupA" | grep -o '"manualEntryKey":"[^"]*"' | head -1 | cut -d'"' -f4)

setupB=$(curl -s -X POST "$BASE_URL/api/v1/auth/totp/setup" -H "Authorization: Bearer $TOKEN2")
TOKEN_B=$(echo "$setupB" | grep -o '"setupToken":"[^"]*"' | head -1 | cut -d'"' -f4)
SECRET_B=$(echo "$setupB" | grep -o '"manualEntryKey":"[^"]*"' | head -1 | cut -d'"' -f4)

CODE_A=$(totp_code "$SECRET_A")
run_test "Prior pending session's setupToken rejected after a newer setup call" 400 \
    -X POST "$BASE_URL/api/v1/auth/totp/verify" \
    -H "Authorization: Bearer $TOKEN2" -H "Content-Type: application/json" \
    -d "{\"setupToken\":\"$TOKEN_A\",\"code\":\"$CODE_A\"}"

CODE_B=$(totp_code "$SECRET_B")
verifyB_status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/totp/verify" \
    -H "Authorization: Bearer $TOKEN2" -H "Content-Type: application/json" \
    -d "{\"setupToken\":\"$TOKEN_B\",\"code\":\"$CODE_B\"}")
if [ "$verifyB_status" -eq 200 ]; then
    pass "The newer (second) setup session still verifies successfully"
else
    fail "Second setup session should still work — got HTTP $verifyB_status"
fi

# ── Test 10: another authenticated user cannot use someone else's setupToken ─
echo ""
echo "--- Test 10: Cross-user setupToken rejected ---"
XSETUP=$(curl -s -X POST "$BASE_URL/api/v1/auth/totp/setup" -H "Authorization: Bearer $PLUS_TOKEN")
XTOKEN=$(echo "$XSETUP" | grep -o '"setupToken":"[^"]*"' | head -1 | cut -d'"' -f4)
run_test "User A cannot verify User B's setupToken" 400 \
    -X POST "$BASE_URL/api/v1/auth/totp/verify" \
    -H "Authorization: Bearer $TOKEN2" -H "Content-Type: application/json" \
    -d "{\"setupToken\":\"$XTOKEN\",\"code\":\"123456\"}"

# ── Test 11: setup response is not cacheable ─────────────────────────────────
echo ""
echo "--- Test 11: Setup/QR responses carry no-store headers ---"
if grep -qi '^Cache-Control: *no-store, *no-cache, *must-revalidate' /tmp/totp_contract_headers.txt \
    && grep -qi '^Pragma: *no-cache' /tmp/totp_contract_headers.txt \
    && grep -qi '^Referrer-Policy: *no-referrer' /tmp/totp_contract_headers.txt; then
    pass "POST /totp/setup response has Cache-Control/Pragma/Referrer-Policy no-store headers"
else
    fail "Missing no-store headers on setup response — got: $(cat /tmp/totp_contract_headers.txt)"
fi

if grep -qi '^X-Frame-Options: *DENY' /tmp/totp_contract_headers.txt; then
    pass "X-Frame-Options: DENY still present on /totp/setup (not weakened)"
else
    fail "X-Frame-Options: DENY missing from /totp/setup response"
fi

qr_headers=$(curl -s -D - -o /dev/null "$BASE_URL/api/v1/auth/totp/qr?token=$XTOKEN")
if echo "$qr_headers" | grep -qi '^Cache-Control: *no-store' \
    && echo "$qr_headers" | grep -qi '^X-Frame-Options: *DENY'; then
    pass "GET /totp/qr also has no-store headers and X-Frame-Options: DENY"
else
    fail "GET /totp/qr missing required headers — got: $qr_headers"
fi
rm -f /tmp/totp_contract_headers.txt

# ── Test 12: no secret/otpauthUri leaked into audit log for the enabled user ─
echo ""
echo "--- Test 12: Audit log for TOTP_ENABLED carries no secret/otpauthUri ---"
audit_row=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -A -c \
    "SELECT COALESCE(old_value::text,'') || COALESCE(new_value::text,'') FROM audit_logs
     WHERE actor_id='$USER_ID1' AND action='TOTP_ENABLED' ORDER BY created_at DESC LIMIT 1;")
if echo "$audit_row" | grep -qi "$MANUAL_KEY1\|otpauth://"; then
    fail "Audit log row contains the secret or otpauthUri!"
else
    pass "Audit log TOTP_ENABLED row contains no secret/otpauthUri"
fi

# ── Cleanup ───────────────────────────────────────────────────────────────────
docker exec fams-postgres psql -U fams_user -d fams_db -q -c "
DELETE FROM totp_backup_codes WHERE user_id IN ('$USER_ID1','$PLUS_USER_ID','$PHONE_USER_ID');
DELETE FROM refresh_tokens WHERE user_id IN ('$USER_ID1','$PLUS_USER_ID','$PHONE_USER_ID');
DELETE FROM audit_logs WHERE actor_id IN ('$USER_ID1','$PLUS_USER_ID','$PHONE_USER_ID');
DELETE FROM users WHERE id IN ('$USER_ID1','$PLUS_USER_ID','$PHONE_USER_ID');
" > /dev/null
# TOKEN2's account was created via register_verified_test_user_token — best-effort cleanup by email.
# It also got TOTP_ENABLED in Test 9, so its audit_logs row must be cleared before the user row
# (same FK reason the first cleanup block clears audit_logs before users).
docker exec fams-postgres psql -U fams_user -d fams_db -q -c "
DELETE FROM totp_backup_codes WHERE user_id = (SELECT id FROM users WHERE email='totp.contract2.${TS}@example.com');
DELETE FROM refresh_tokens WHERE user_id = (SELECT id FROM users WHERE email='totp.contract2.${TS}@example.com');
DELETE FROM audit_logs WHERE actor_id = (SELECT id FROM users WHERE email='totp.contract2.${TS}@example.com');
DELETE FROM users WHERE email='totp.contract2.${TS}@example.com';
" > /dev/null

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
