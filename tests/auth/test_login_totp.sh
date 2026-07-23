#!/usr/bin/env bash
# Tests for TOTP-gated login (task 13)
# POST /api/v1/auth/login       — returns totpRequired=true when TOTP is enabled
# POST /api/v1/auth/login/totp  — exchanges pendingToken + code for real tokens
# Usage: BASE_URL=http://localhost:8080 bash test_login_totp.sh
#
# Requires: python3 (for TOTP code generation)

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

run_test() {
    local name="$1"
    local expected_status="$2"
    local curl_args=("${@:3}")
    local actual_status
    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "${curl_args[@]}")
    if [ "$actual_status" -eq "$expected_status" ]; then
        echo "PASS: $name (HTTP $actual_status)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $name — expected HTTP $expected_status, got HTTP $actual_status"
        FAIL=$((FAIL + 1))
    fi
}

# Compute TOTP code from a base32 secret using python3
totp_code() {
    local secret="$1"
    python3 - "$secret" <<'PYEOF'
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

echo "=== TOTP Login Tests ==="
echo "Target: $BASE_URL"
echo ""

# ── Setup: enable TOTP on admin account ──────────────────────────

echo "--- Setup: Login as admin and enable TOTP ---"
admin_login=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
admin_body=$(echo "$admin_login" | head -n -1)
admin_status=$(echo "$admin_login" | tail -n 1)

if [ "$admin_status" -ne 200 ]; then
    echo "SETUP FAIL: Admin login failed (HTTP $admin_status)"
    exit 1
fi
ADMIN_TOKEN=$(echo "$admin_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4)

# Initiate TOTP setup
setup_body=$(curl -s \
    -X POST "$BASE_URL/api/v1/auth/totp/setup" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
SETUP_TOKEN=$(echo "$setup_body" | grep -o '"setupToken":"[^"]*"' | head -1 | cut -d'"' -f4)
MANUAL_KEY=$(echo "$setup_body" | grep -o '"manualEntryKey":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$SETUP_TOKEN" ] || [ -z "$MANUAL_KEY" ]; then
    echo "SETUP FAIL: Could not get TOTP setup token"
    exit 1
fi

# Compute and submit TOTP code to enable 2FA
CODE=$(totp_code "$MANUAL_KEY")
enable_status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/totp/verify" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"setupToken\":\"$SETUP_TOKEN\",\"code\":\"$CODE\"}")

if [ "$enable_status" -ne 200 ]; then
    echo "SETUP FAIL: Could not enable TOTP on admin (HTTP $enable_status)"
    exit 1
fi
echo "Setup complete: TOTP enabled on admin account."
echo ""

# ── Test 1: Normal login now returns totpRequired=true ────────────

echo "--- Test 1: Login with TOTP-enabled account returns totpRequired=true ---"
login_response=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_response" | head -n -1)
login_status=$(echo "$login_response" | tail -n 1)

PENDING_TOKEN=""
if [ "$login_status" -eq 200 ]; then
    totp_required=$(echo "$login_body" | grep -o '"totpRequired":true' || true)
    PENDING_TOKEN=$(echo "$login_body" | grep -o '"pendingToken":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ -n "$totp_required" ] && [ -n "$PENDING_TOKEN" ]; then
        echo "PASS: Login returns totpRequired=true with pendingToken (HTTP 200)"
        PASS=$((PASS + 1))
    else
        echo "FAIL: Expected totpRequired=true and pendingToken, got: $login_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Expected HTTP 200, got HTTP $login_status"
    FAIL=$((FAIL + 1))
fi

# ── Test 2: login/totp with missing pendingToken → 400 ────────────

echo ""
echo "--- Test 2: login/totp - missing pendingToken ---"
run_test "login/totp - missing pendingToken" 400 \
    -X POST "$BASE_URL/api/v1/auth/login/totp" \
    -H "Content-Type: application/json" \
    -d '{"code":"123456"}'

# ── Test 3: login/totp with missing code → 401 ────────────────────
# Issue #5 (docs/issues/ISSUES.md): `code` is no longer @NotBlank at the DTO level since
# backupCode is now a valid alternative — so this no longer 400s at validation. It still
# gets rejected, just later: "some-token" isn't a real pending token, so the service's
# Redis lookup fails first with 401 (same status it would 401 with a real pending token
# and neither code nor backupCode supplied — see LoginTotpService's explicit check).

echo ""
echo "--- Test 3: login/totp - missing code ---"
run_test "login/totp - missing code" 401 \
    -X POST "$BASE_URL/api/v1/auth/login/totp" \
    -H "Content-Type: application/json" \
    -d '{"pendingToken":"some-token"}'

# ── Test 4: login/totp with invalid code format → 400 ─────────────

echo ""
echo "--- Test 4: login/totp - non-digit code ---"
run_test "login/totp - non-digit code" 400 \
    -X POST "$BASE_URL/api/v1/auth/login/totp" \
    -H "Content-Type: application/json" \
    -d '{"pendingToken":"some-token","code":"abcdef"}'

# ── Test 5: login/totp with invalid pendingToken → 401 ────────────

echo ""
echo "--- Test 5: login/totp - invalid pendingToken ---"
run_test "login/totp - invalid pendingToken" 401 \
    -X POST "$BASE_URL/api/v1/auth/login/totp" \
    -H "Content-Type: application/json" \
    -d '{"pendingToken":"00000000-0000-0000-0000-000000000000","code":"123456"}'

# ── Test 6: login/totp with wrong TOTP code → 401 ─────────────────

echo ""
echo "--- Test 6: login/totp - wrong TOTP code ---"
if [ -n "$PENDING_TOKEN" ]; then
    # Get a fresh pending token (the one from test 1 may have been consumed by test 5 attempt)
    fresh_login=$(curl -s \
        -X POST "$BASE_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d '{"email":"admin@fams.com","password":"Admin@1234"}')
    FRESH_PENDING=$(echo "$fresh_login" | grep -o '"pendingToken":"[^"]*"' | head -1 | cut -d'"' -f4)

    run_test "login/totp - wrong TOTP code" 401 \
        -X POST "$BASE_URL/api/v1/auth/login/totp" \
        -H "Content-Type: application/json" \
        -d "{\"pendingToken\":\"$FRESH_PENDING\",\"code\":\"000000\"}"
else
    echo "SKIP: No pending token available"
    FAIL=$((FAIL + 1))
fi

# ── Test 7: login/totp happy path with correct code → 200 ─────────

echo ""
echo "--- Test 7: login/totp - correct TOTP code (full flow) ---"
# Get another fresh pending token
pending_login=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@fams.com","password":"Admin@1234"}')
pending_body=$(echo "$pending_login" | head -n -1)
pending_status=$(echo "$pending_login" | tail -n 1)
VALID_PENDING=$(echo "$pending_body" | grep -o '"pendingToken":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ "$pending_status" -eq 200 ] && [ -n "$VALID_PENDING" ]; then
    VALID_CODE=$(totp_code "$MANUAL_KEY")
    totp_login=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/v1/auth/login/totp" \
        -H "Content-Type: application/json" \
        -d "{\"pendingToken\":\"$VALID_PENDING\",\"code\":\"$VALID_CODE\"}")
    totp_login_body=$(echo "$totp_login" | head -n -1)
    totp_login_status=$(echo "$totp_login" | tail -n 1)

    if [ "$totp_login_status" -eq 200 ]; then
        access=$(echo "$totp_login_body" | grep -o '"accessToken":"[^"]*"' | head -1)
        refresh=$(echo "$totp_login_body" | grep -o '"refreshToken":"[^"]*"' | head -1)
        if [ -n "$access" ] && [ -n "$refresh" ]; then
            echo "PASS: login/totp happy path (HTTP 200, tokens present)"
            PASS=$((PASS + 1))
            NEW_ADMIN_TOKEN=$(echo "$access" | cut -d'"' -f4)
        else
            echo "FAIL: HTTP 200 but tokens missing: $totp_login_body"
            FAIL=$((FAIL + 1))
        fi
    else
        echo "FAIL: Expected HTTP 200, got HTTP $totp_login_status — $totp_login_body"
        FAIL=$((FAIL + 1))
    fi
else
    echo "FAIL: Could not get fresh pending token for happy path"
    FAIL=$((FAIL + 1))
fi

# ── Cleanup: disable TOTP on admin ───────────────────────────────
# Issue #5 (docs/issues/ISSUES.md): /totp/disable now requires re-proof of identity
# (password/code/backupCode) — a bare Bearer token is no longer enough. Without this fix,
# every run of this script would leave admin@fams.com stuck with totp_enabled=true,
# breaking every OTHER test suite that logs in as admin expecting immediate tokens.

echo ""
echo "--- Cleanup: Disabling TOTP on admin ---"
CLEANUP_TOKEN="${NEW_ADMIN_TOKEN:-$ADMIN_TOKEN}"
disable_status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/totp/disable" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $CLEANUP_TOKEN" \
    -d '{"password":"Admin@1234"}')

if [ "$disable_status" -eq 200 ]; then
    echo "Cleanup: TOTP disabled on admin."
else
    echo "WARNING: Could not disable TOTP on admin (HTTP $disable_status). Manual cleanup may be needed."
    echo "Manual fix: docker exec fams-postgres psql -U fams_user -d fams_db -c \"UPDATE users SET totp_enabled=false, totp_secret=NULL WHERE email='admin@fams.com';\""
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
