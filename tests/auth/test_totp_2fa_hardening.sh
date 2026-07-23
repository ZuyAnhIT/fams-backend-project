#!/usr/bin/env bash
# Tests for Issue #5 (docs/issues/ISSUES.md) — three real 2FA bugs found by investigation:
#   1. /totp/disable required no re-authentication (a stolen JWT could strip 2FA)
#   2. totp_secret was stored in plaintext
#   3. no backup/recovery codes — losing the authenticator meant permanent lockout
# Usage: BASE_URL=http://localhost:8080 bash test_totp_2fa_hardening.sh
#
# Requires: python3 (TOTP code computation), docker exec access to fams-postgres.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/test_helpers.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
TS=$(date +%s)

pass() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

totp_code() {
    python3 - "$1" <<'PYEOF'
import sys, hmac, hashlib, struct, time, base64
secret = sys.argv[1]
pad = (8 - len(secret) % 8) % 8
key = base64.b32decode((secret + '=' * pad).upper())
counter = int(time.time()) // 30
msg = struct.pack('>Q', counter)
h = hmac.new(key, msg, hashlib.sha1).digest()
o = h[-1] & 15
code = (struct.unpack('>I', h[o:o+4])[0] & 0x7fffffff) % 1000000
print(f'{code:06d}')
PYEOF
}

# Sets globals SECRET and ENABLE_RESPONSE. Must be called directly (not via `$(...)`),
# since a subshell would lose the SECRET assignment the rest of the script depends on.
enable_totp() {
    local setup_resp setup_token code
    setup_resp=$(curl -s -X POST "$BASE_URL/api/v1/auth/totp/setup" -H "Authorization: Bearer $TOKEN")
    SECRET=$(echo "$setup_resp" | grep -o '"manualEntryKey":"[^"]*"' | cut -d'"' -f4)
    setup_token=$(echo "$setup_resp" | grep -o '"setupToken":"[^"]*"' | cut -d'"' -f4)
    code=$(totp_code "$SECRET")
    ENABLE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/auth/totp/verify" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
        -d "{\"setupToken\":\"$setup_token\",\"code\":\"$code\"}")
}

echo "=== 2FA Hardening Tests (Issue #5) ==="
echo "Target: $BASE_URL"
echo ""

EMAIL="totp_hardening_${TS}@fams.com"
TOKEN=$(register_verified_test_user_token "$BASE_URL" "2FA Hardening Test" "$EMAIL")
if [ -z "$TOKEN" ]; then
    echo "SETUP FAILED: could not obtain a test user token"
    exit 1
fi

# ── Test 1: Enabling 2FA returns 8 backup codes ──────────────────────────────
echo "--- Test 1: Enable 2FA returns backup codes ---"
enable_totp
backup_codes=$(echo "$ENABLE_RESPONSE" | grep -o '"[A-Z0-9]\{8\}"' | tr -d '"')
count=$(echo "$backup_codes" | grep -c . || true)
if [ "$count" -eq 8 ]; then
    pass "Enable 2FA returned 8 backup codes"
else
    fail "Expected 8 backup codes, got $count. Body: $ENABLE_RESPONSE"
fi

# ── Test 2: Secret stored encrypted, not plaintext ───────────────────────────
echo ""
echo "--- Test 2: totp_secret stored encrypted (not equal to plaintext secret) ---"
stored_secret=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT totp_secret FROM users WHERE email='$EMAIL';" | tr -d ' \n')
if [ -n "$stored_secret" ] && [ "$stored_secret" != "$SECRET" ]; then
    pass "Stored totp_secret differs from plaintext manualEntryKey (encrypted at rest)"
else
    fail "Stored secret matches plaintext or is empty — encryption not applied. stored='$stored_secret' plain='$SECRET'"
fi

# ── Test 3: Disable with no body → 401 ───────────────────────────────────────
echo ""
echo "--- Test 3: Disable with empty body rejected ---"
run_test_disable() {
    local name="$1" expected="$2" body="$3"
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/totp/disable" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "$body")
    if [ "$status" -eq "$expected" ]; then
        pass "$name (HTTP $status)"
    else
        fail "$name — expected HTTP $expected, got HTTP $status"
    fi
}
run_test_disable "Disable with empty body rejected" 401 '{}'

# ── Test 4: Disable with wrong password → 401 ────────────────────────────────
echo ""
echo "--- Test 4: Disable with wrong password rejected ---"
run_test_disable "Disable with wrong password rejected" 401 '{"password":"WrongPass1"}'

# ── Test 5: Disable with correct password → 200 ──────────────────────────────
echo ""
echo "--- Test 5: Disable with correct password succeeds ---"
run_test_disable "Disable with correct password succeeds" 200 '{"password":"TestPass1"}'

# ── Test 6: Disabling again (already disabled) → 400 ────────────────────────
echo ""
echo "--- Test 6: Disable when not enabled → 400 ---"
run_test_disable "Disable when not enabled rejected" 400 '{"password":"TestPass1"}'

# ── Test 7: Re-enable, disable via TOTP code ─────────────────────────────────
echo ""
echo "--- Test 7: Re-enable then disable via TOTP code ---"
enable_totp
CODE_FOR_DISABLE=$(totp_code "$SECRET")
run_test_disable "Disable via TOTP code succeeds" 200 "{\"code\":\"$CODE_FOR_DISABLE\"}"

# ── Test 8: Re-enable, backup code login + single-use + disable via backup code ──
echo ""
echo "--- Test 8: Backup code login (single-use) + disable via backup code ---"
enable_totp
mapfile -t codes < <(echo "$ENABLE_RESPONSE" | grep -o '"[A-Z0-9]\{8\}"' | tr -d '"')
LOGIN_BACKUP_CODE="${codes[0]}"
DISABLE_BACKUP_CODE="${codes[1]}"

pending=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"TestPass1\"}" | grep -o '"pendingToken":"[^"]*"' | cut -d'"' -f4)
login_status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/login/totp" \
    -H "Content-Type: application/json" -d "{\"pendingToken\":\"$pending\",\"backupCode\":\"$LOGIN_BACKUP_CODE\"}")
if [ "$login_status" -eq 200 ]; then
    pass "Login with backup code succeeds (HTTP 200)"
else
    fail "Login with backup code — expected 200, got $login_status"
fi

pending2=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"TestPass1\"}" | grep -o '"pendingToken":"[^"]*"' | cut -d'"' -f4)
reuse_status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/auth/login/totp" \
    -H "Content-Type: application/json" -d "{\"pendingToken\":\"$pending2\",\"backupCode\":\"$LOGIN_BACKUP_CODE\"}")
if [ "$reuse_status" -eq 401 ]; then
    pass "Reusing the same backup code rejected (HTTP 401, single-use enforced)"
else
    fail "Backup code reuse — expected 401, got $reuse_status"
fi

run_test_disable "Disable via a different, unused backup code succeeds" 200 "{\"backupCode\":\"$DISABLE_BACKUP_CODE\"}"

# ── Test 9: Backup codes are gone after disable ──────────────────────────────
echo ""
echo "--- Test 9: Backup codes deleted after disable ---"
remaining=$(docker exec fams-postgres psql -U fams_user -d fams_db -t -c \
    "SELECT COUNT(*) FROM totp_backup_codes bc JOIN users u ON u.id=bc.user_id WHERE u.email='$EMAIL';" | tr -d ' \n')
if [ "$remaining" = "0" ]; then
    pass "All backup codes deleted after disabling 2FA"
else
    fail "Expected 0 remaining backup codes, found $remaining"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
