#!/usr/bin/env bash
# Tests for POST /api/v1/auth/refresh-token
# Usage: BASE_URL=http://localhost:8080 bash test_refresh_token.sh

set -uo pipefail

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

echo "=== Refresh Token Tests ==="
echo "Target: $BASE_URL"
echo ""

REFRESH_URL="$BASE_URL/api/v1/auth/refresh-token"

# ─── Setup: login to get refresh token ────────────────────────────────────────
echo "--- Setup: Login as platform admin ---"
login_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"identifier":"admin@fams.com","password":"Admin@1234"}')
login_body=$(echo "$login_resp" | head -n -1)
login_status=$(echo "$login_resp" | tail -n 1)
if [ "$login_status" -ne 200 ]; then
    echo "SETUP FAILED: Could not login (HTTP $login_status)"
    exit 1
fi
REFRESH_TOKEN=$(echo "$login_body" | grep -o '"refreshToken":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$REFRESH_TOKEN" ]; then
    echo "SETUP FAILED: Could not extract refreshToken from login response"
    exit 1
fi
echo "Refresh token obtained."
echo ""

# ─── Test 1: Missing refreshToken field → 400 ─────────────────────────────────
echo "--- Test 1: Missing refreshToken field → 400 ---"
run_test "Missing refreshToken" 400 \
    -X POST "$REFRESH_URL" \
    -H "Content-Type: application/json" \
    -d '{}'

# ─── Test 2: Empty refreshToken → 400 ────────────────────────────────────────
echo ""
echo "--- Test 2: Empty refreshToken value → 400 ---"
run_test "Empty refreshToken" 400 \
    -X POST "$REFRESH_URL" \
    -H "Content-Type: application/json" \
    -d '{"refreshToken":""}'

# ─── Test 3: Invalid/unknown token → 401 ─────────────────────────────────────
echo ""
echo "--- Test 3: Invalid refresh token → 401 ---"
run_test "Invalid token" 401 \
    -X POST "$REFRESH_URL" \
    -H "Content-Type: application/json" \
    -d '{"refreshToken":"totally-invalid-token-value"}'

# ─── Test 4: Valid refresh token → 200 + new tokens ───────────────────────────
echo ""
echo "--- Test 4: Valid refresh token → 200 ---"
refresh_resp=$(curl -s -w "\n%{http_code}" \
    -X POST "$REFRESH_URL" \
    -H "Content-Type: application/json" \
    -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}")
refresh_body=$(echo "$refresh_resp" | head -n -1)
refresh_status=$(echo "$refresh_resp" | tail -n 1)
if [ "$refresh_status" -eq 200 ]; then
    echo "PASS: Valid token refresh (HTTP $refresh_status)"
    PASS=$((PASS + 1))
    NEW_ACCESS=$(echo "$refresh_body" | grep -o '"accessToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    NEW_REFRESH=$(echo "$refresh_body" | grep -o '"refreshToken":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    MSG=$(echo "$refresh_body" | grep -o '"message":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    echo "  message: $MSG"
    echo "  accessToken present: $([ -n "$NEW_ACCESS" ] && echo yes || echo no)"
    echo "  refreshToken present: $([ -n "$NEW_REFRESH" ] && echo yes || echo no)"
else
    echo "FAIL: Valid token refresh — expected HTTP 200, got HTTP $refresh_status"
    FAIL=$((FAIL + 1))
    NEW_REFRESH=""
fi

# ─── Test 5: Old (rotated) refresh token is now revoked → 401 ─────────────────
echo ""
echo "--- Test 5: Old refresh token after rotation → 401 ---"
run_test "Revoked (old) token" 401 \
    -X POST "$REFRESH_URL" \
    -H "Content-Type: application/json" \
    -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}"

# ─── Test 6: New access token is accepted by an authenticated endpoint ─────────
echo ""
echo "--- Test 6: New access token works on protected endpoint ---"
if [ -n "${NEW_ACCESS:-}" ]; then
    run_test "New access token accepted" 200 \
        -X GET "$BASE_URL/api/v1/auth/me" \
        -H "Authorization: Bearer $NEW_ACCESS"
else
    echo "SKIP: No access token from test 4"
fi

# ─── Test 7: New refresh token can itself be refreshed → 200 ──────────────────
echo ""
echo "--- Test 7: Rotated refresh token is still valid → 200 ---"
if [ -n "${NEW_REFRESH:-}" ]; then
    run_test "Rotated token refresh" 200 \
        -X POST "$REFRESH_URL" \
        -H "Content-Type: application/json" \
        -d "{\"refreshToken\":\"$NEW_REFRESH\"}"
else
    echo "SKIP: No rotated refresh token from test 4"
fi

echo ""
echo "=== Results ==="
echo "PASSED: $PASS"
echo "FAILED: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
